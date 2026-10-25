"""Training: turn a dataset into a model artifact plus the metadata that makes it traceable.

The gradient-boosted tree is one *backend* here, not the architecture. A `Trainer` is anything that can fit a
feature matrix and score a vector, which keeps the pipeline testable without a native library and lets the
backend be swapped without touching dataset versioning, threshold derivation or publication.

Two properties are load-bearing and both are tested:

* **Determinism.** The same dataset and the same trainer produce the same ``model_id``. That is what makes
  "which data trained this model" answerable by construction rather than by bookkeeping.
* **Thresholds travel with the model.** They are derived from this model's own score distribution, so a
  published model can never be paired with thresholds tuned for a different one.
"""

from __future__ import annotations

import hashlib
import json
import math
from dataclasses import dataclass
from typing import Protocol, Sequence

from app.dataset import CHEAT, Dataset
from app.schemas import Thresholds


class Trainer(Protocol):
    """Fits a feature matrix and scores a vector in ``[0, 1]``, cheat-direction."""

    @property
    def name(self) -> str: ...

    def fit(self, features: Sequence[Sequence[float]], labels: Sequence[int]) -> "Predictor": ...


class Predictor(Protocol):
    def score(self, features: Sequence[float]) -> float: ...

    def artifact(self) -> dict: ...


@dataclass(frozen=True)
class TrainedModel:
    model_id: str
    dataset_id: str
    trainer_name: str
    thresholds: Thresholds
    artifact: dict
    feature_dimensions: int


class _LogisticPredictor:
    def __init__(self, weights: list[float], bias: float) -> None:
        self._weights = weights
        self._bias = bias

    def score(self, features: Sequence[float]) -> float:
        total = self._bias
        for weight, value in zip(self._weights, features):
            total += weight * value
        return 1.0 / (1.0 + math.exp(-max(-60.0, min(60.0, total))))

    def artifact(self) -> dict:
        return {"kind": "logistic", "weights": self._weights, "bias": self._bias}


class LogisticTrainer:
    """A dependency-free backend: plain batch gradient descent, fixed iteration count.

    Deterministic by construction (no shuffling, no random init), which is what the determinism requirement
    needs. It is a real model, not a stub — just a deliberately simple one.
    """

    def __init__(self, iterations: int = 400, learning_rate: float = 0.5) -> None:
        self._iterations = iterations
        self._learning_rate = learning_rate

    @property
    def name(self) -> str:
        return "logistic-v1"

    def fit(self, features: Sequence[Sequence[float]], labels: Sequence[int]) -> _LogisticPredictor:
        if not features:
            raise ValueError("cannot fit on an empty dataset")

        dimensions = len(features[0])
        weights = [0.0] * dimensions
        bias = 0.0
        count = len(features)

        for _ in range(self._iterations):
            gradient = [0.0] * dimensions
            bias_gradient = 0.0
            for row, label in zip(features, labels):
                total = bias + sum(w * v for w, v in zip(weights, row))
                predicted = 1.0 / (1.0 + math.exp(-max(-60.0, min(60.0, total))))
                error = predicted - label
                bias_gradient += error
                for index in range(dimensions):
                    gradient[index] += error * row[index]
            bias -= self._learning_rate * bias_gradient / count
            for index in range(dimensions):
                weights[index] -= self._learning_rate * gradient[index] / count

        return _LogisticPredictor(weights, bias)


class LightGBMTrainer:
    """The production backend. Imported lazily so the service runs without the native library present."""

    @property
    def name(self) -> str:
        return "lightgbm-v1"

    def fit(self, features: Sequence[Sequence[float]], labels: Sequence[int]):
        import lightgbm  # noqa: PLC0415 - optional dependency, resolved at training time

        booster = lightgbm.LGBMClassifier(
            n_estimators=120,
            num_leaves=15,
            min_child_samples=5,
            deterministic=True,
            force_row_wise=True,
            random_state=0,
            verbose=-1,
        )
        booster.fit(list(features), list(labels))

        class _Booster:
            def score(self, row: Sequence[float]) -> float:
                return float(booster.predict_proba([list(row)])[0][1])

            def artifact(self) -> dict:
                return {"kind": "lightgbm", "model": booster.booster_.model_to_string()}

        return _Booster()


def available_trainers() -> dict[str, Trainer]:
    """Backends this deployment can actually use."""
    trainers: dict[str, Trainer] = {"logistic": LogisticTrainer()}
    try:
        import lightgbm  # noqa: F401, PLC0415

        trainers["lightgbm"] = LightGBMTrainer()
    except Exception:
        pass
    return trainers


def _model_id(dataset_id: str, trainer_name: str) -> str:
    digest = hashlib.sha256(f"{dataset_id}\x00{trainer_name}".encode("utf-8"))
    return "m-" + digest.hexdigest()[:16]


def derive_thresholds(predictor: Predictor, dataset: Dataset) -> Thresholds:
    """Pick thresholds from this model's own score distribution over its own training data.

    The cheat bound sits at the 20th percentile of cheat-labelled scores and the legit bound at the 80th
    percentile of legit-labelled scores, so each bound tolerates the model's own weakest fifth. If the two
    distributions overlap the bounds are pushed apart to keep ``legit_like < cheat_like``; the band between
    them is exactly the region the model declines to call.
    """
    cheat_scores = sorted(
        predictor.score(sample.features) for sample in dataset.samples if sample.label == CHEAT
    )
    legit_scores = sorted(
        predictor.score(sample.features) for sample in dataset.samples if sample.label != CHEAT
    )

    cheat_like = _percentile(cheat_scores, 0.2)
    legit_like = _percentile(legit_scores, 0.8)

    if legit_like >= cheat_like:
        middle = (legit_like + cheat_like) / 2.0
        cheat_like = min(1.0, middle + 0.01)
        legit_like = max(0.0, middle - 0.01)

    return Thresholds(cheat_like=round(cheat_like, 6), legit_like=round(legit_like, 6))


def _percentile(values: list[float], fraction: float) -> float:
    if not values:
        return 0.0
    index = min(len(values) - 1, max(0, int(round(fraction * (len(values) - 1)))))
    return values[index]


def train_model(dataset: Dataset, trainer: Trainer) -> TrainedModel:
    """Fit a model for a dataset and package it with its thresholds."""
    if not dataset.is_trainable():
        raise ValueError(
            "dataset needs both cheat and legit samples; "
            f"got {dataset.cheat_count} cheat and {dataset.legit_count} legit"
        )

    features = [list(sample.features) for sample in dataset.samples]
    labels = [1 if sample.label == CHEAT else 0 for sample in dataset.samples]

    predictor = trainer.fit(features, labels)
    thresholds = derive_thresholds(predictor, dataset)

    return TrainedModel(
        model_id=_model_id(dataset.dataset_id, trainer.name),
        dataset_id=dataset.dataset_id,
        trainer_name=trainer.name,
        thresholds=thresholds,
        artifact=predictor.artifact(),
        feature_dimensions=len(features[0]),
    )


def predictor_from_artifact(artifact: dict) -> Predictor:
    """Rebuild a scorer from a published artifact.

    Only the logistic artifact is reconstructible today. A LightGBM artifact would need the booster parsed
    back out of its model string, which is ticket-worthy work of its own; until then that backend publishes
    a model the service cannot serve, and saying so is better than pretending otherwise.
    """
    if artifact.get("kind") != "logistic":
        raise ValueError(f"cannot rebuild a scorer from artifact kind {artifact.get('kind')!r}")
    return _LogisticPredictor(list(artifact["weights"]), float(artifact["bias"]))


def artifact_bytes(model: TrainedModel) -> bytes:
    """The model file, as it would be written to disk."""
    return json.dumps(
        {
            "model_id": model.model_id,
            "dataset_id": model.dataset_id,
            "trainer": model.trainer_name,
            "feature_dimensions": model.feature_dimensions,
            "thresholds": {
                "cheat_like": model.thresholds.cheat_like,
                "legit_like": model.thresholds.legit_like,
            },
            "artifact": model.artifact,
        },
        indent=2,
        sort_keys=True,
    ).encode("utf-8")
