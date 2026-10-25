"""What the service knows about the model currently in effect, and how a score becomes a verdict.

The model here is a placeholder. It exists so the contract can be exercised end to end while the real
gradient-boosted tree, its training and its publication remain tickets #13-#16.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Protocol, Sequence

from app.schemas import FEATURE_DIMENSIONS, Thresholds, VerdictLabel


@dataclass(frozen=True)
class Model:
    """One published model version and the traceability chain behind it."""

    model_id: str
    dataset_id: str
    thresholds: Thresholds
    feature_dimensions: int = FEATURE_DIMENSIONS


class Scorer(Protocol):
    """Turns a feature vector into a cheat-direction score in ``[0, 1]``."""

    def score(self, features: Sequence[float]) -> float: ...


class StubScorer:
    """Contract-phase stand-in for the gradient-boosted tree.

    It answers a constant so that the corpus can pin the *shape* of a verdict without pinning a model's
    behaviour. It says nothing about detection quality, which is out of scope for the contract.
    """

    def score(self, features: Sequence[float]) -> float:
        return 0.0


DEFAULT_MODEL = Model(
    model_id="m-0001",
    dataset_id="ds-0001",
    thresholds=Thresholds(cheat_like=0.7, legit_like=0.3),
)


class ModelRegistry:
    """The model currently in effect, or nothing at all on a fresh deployment."""

    def __init__(self, model: Model | None = None) -> None:
        self._model = model

    @classmethod
    def with_default_model(cls) -> "ModelRegistry":
        return cls(DEFAULT_MODEL)

    @classmethod
    def cold_start(cls) -> "ModelRegistry":
        """A deployment that has never trained or published a model."""
        return cls(None)

    def current(self) -> Model | None:
        return self._model


def classify(score: float, thresholds: Thresholds) -> VerdictLabel:
    """Map a score to a conclusion using the thresholds published with this model version."""
    if score >= thresholds.cheat_like:
        return VerdictLabel.CHEAT_LIKE
    if score <= thresholds.legit_like:
        return VerdictLabel.LEGIT_LIKE
    return VerdictLabel.UNCERTAIN
