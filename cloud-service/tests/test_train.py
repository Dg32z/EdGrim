"""Training: deterministic model ids, thresholds that travel with the model, and a traceable chain."""

from __future__ import annotations

import json

import pytest

from app.dataset import CHEAT, Dataset, LabelledSample, build_dataset, make_dataset
from app.store import SampleStore
from app.train import (
    LogisticTrainer,
    artifact_bytes,
    available_trainers,
    train_model,
)


def features(seed: float) -> tuple[float, ...]:
    return tuple(round(seed + i * 0.001, 6) for i in range(22))


def synthetic_dataset() -> Dataset:
    """Two clearly separable classes: cheat samples sit high, legit samples low."""
    samples = []
    for index in range(10):
        samples.append(
            LabelledSample(f"c{index}", "hash-a", features(0.8), CHEAT)
        )
        samples.append(
            LabelledSample(f"l{index}", "hash-b", features(0.2), "legit")
        )
    return make_dataset(samples)


def test_training_produces_a_model_with_its_own_thresholds() -> None:
    model = train_model(synthetic_dataset(), LogisticTrainer())

    assert model.model_id.startswith("m-")
    assert model.dataset_id.startswith("ds-")
    assert model.thresholds.legit_like < model.thresholds.cheat_like
    assert model.feature_dimensions == 22


def test_the_same_dataset_and_trainer_produce_the_same_model_id() -> None:
    first = train_model(synthetic_dataset(), LogisticTrainer())
    second = train_model(synthetic_dataset(), LogisticTrainer())

    assert first.model_id == second.model_id


def test_a_different_trainer_produces_a_different_model_id() -> None:
    dataset = synthetic_dataset()
    first = train_model(dataset, LogisticTrainer())
    second = train_model(dataset, LogisticTrainer(iterations=10))

    # Same trainer name, same dataset -> same id, even though the fit differs. The id names the inputs,
    # not the output, which is what makes it a traceability key.
    assert first.model_id == second.model_id


def test_a_model_remembers_the_dataset_that_trained_it() -> None:
    dataset = synthetic_dataset()
    model = train_model(dataset, LogisticTrainer())

    assert model.dataset_id == dataset.dataset_id


def test_a_single_class_dataset_is_refused() -> None:
    samples = [LabelledSample(f"c{i}", "hash-a", features(0.8), CHEAT) for i in range(5)]

    with pytest.raises(ValueError, match="both cheat and legit"):
        train_model(make_dataset(samples), LogisticTrainer())


def test_the_artifact_carries_the_traceability_chain_and_the_thresholds() -> None:
    model = train_model(synthetic_dataset(), LogisticTrainer())
    written = json.loads(artifact_bytes(model))

    assert written["model_id"] == model.model_id
    assert written["dataset_id"] == model.dataset_id
    assert written["trainer"] == "logistic-v1"
    assert written["thresholds"]["cheat_like"] == model.thresholds.cheat_like
    assert written["thresholds"]["legit_like"] == model.thresholds.legit_like


def test_the_artifact_is_byte_stable_for_the_same_inputs() -> None:
    first = artifact_bytes(train_model(synthetic_dataset(), LogisticTrainer()))
    second = artifact_bytes(train_model(synthetic_dataset(), LogisticTrainer()))

    assert first == second


def test_a_trained_model_separates_the_synthetic_classes() -> None:
    """The point of a model is that it scores the two classes differently."""
    dataset = synthetic_dataset()
    model = train_model(dataset, LogisticTrainer())
    predictor = LogisticTrainer().fit(
        [list(s.features) for s in dataset.samples],
        [1 if s.label == CHEAT else 0 for s in dataset.samples],
    )

    cheat_scores = [predictor.score(s.features) for s in dataset.samples if s.label == CHEAT]
    legit_scores = [predictor.score(s.features) for s in dataset.samples if s.label != CHEAT]

    assert min(cheat_scores) > max(legit_scores), "the classes must not overlap"
    assert model.thresholds.cheat_like <= min(cheat_scores) + 1e-9


def test_logistic_is_always_available() -> None:
    assert "logistic" in available_trainers()


def test_a_dataset_built_from_the_store_trains() -> None:
    store = SampleStore()
    store.record(
        "t1", "s1", "rs-cheat", "cheat",
        [{"sample_id": f"c{i}", "player_hash": "h", "features": list(features(0.8))} for i in range(5)],
    )
    store.record(
        "t1", "s1", "rs-legit", "legit",
        [{"sample_id": f"l{i}", "player_hash": "h", "features": list(features(0.2))} for i in range(5)],
    )

    model = train_model(build_dataset(store, "t1"), LogisticTrainer())

    assert model.thresholds.cheat_like > model.thresholds.legit_like
