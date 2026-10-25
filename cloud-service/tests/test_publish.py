"""Publishing and rolling back: a pointer that moves, and a history that is never rewritten."""

from __future__ import annotations

from app.metrics import Metrics
from app.publish import HealthPolicy, ModelPublisher, should_rollback
from app.schemas import Thresholds
from app.train import TrainedModel


def model(model_id: str, dataset_id: str = "ds-1") -> TrainedModel:
    return TrainedModel(
        model_id=model_id,
        dataset_id=dataset_id,
        trainer_name="logistic-v1",
        thresholds=Thresholds(cheat_like=0.7, legit_like=0.3),
        artifact={"kind": "logistic", "weights": [0.0] * 22, "bias": 0.0},
        feature_dimensions=22,
    )


def test_publishing_moves_the_current_pointer() -> None:
    publisher = ModelPublisher()
    publisher.publish(model("m-1"))

    assert publisher.current().model_id == "m-1"


def test_rolling_back_returns_the_previous_model() -> None:
    publisher = ModelPublisher()
    publisher.publish(model("m-1"))
    publisher.publish(model("m-2", "ds-2"))

    restored = publisher.rollback()

    assert restored.model_id == "m-1"
    assert publisher.current().model_id == "m-1"


def test_rolling_back_without_a_previous_model_changes_nothing() -> None:
    publisher = ModelPublisher()
    publisher.publish(model("m-1"))

    assert publisher.rollback() is None
    assert publisher.current().model_id == "m-1", "a rollback that lands nowhere must not take the model away"


def test_two_versions_are_retained_so_a_rollback_is_always_possible() -> None:
    publisher = ModelPublisher()
    publisher.publish(model("m-1"))
    publisher.publish(model("m-2", "ds-2"))

    assert publisher.retained_versions() == 2


def test_the_rolled_back_model_is_never_deleted_so_it_keeps_answering_identically() -> None:
    publisher = ModelPublisher()
    publisher.publish(model("m-1"))
    publisher.publish(model("m-2", "ds-2"))
    publisher.rollback()

    assert any(record.model.model_id == "m-2" for record in publisher.history)


def test_no_verdict_is_no_opinion() -> None:
    """A model cannot be judged before it has answered anything."""
    assert should_rollback(Metrics(), HealthPolicy(min_verdicts_before_judging=20)) is None


def test_a_high_error_rate_triggers_a_rollback() -> None:
    metrics = Metrics()
    for _ in range(10):
        metrics.record_request()
    for _ in range(5):
        metrics.record_error()
    metrics.record_verdicts(["legit_like"] * 30)

    reason = should_rollback(metrics, HealthPolicy(min_verdicts_before_judging=20))

    assert reason is not None
    assert "error rate" in reason


def test_a_model_calling_everything_cheat_triggers_a_rollback() -> None:
    metrics = Metrics()
    metrics.record_verdicts(["cheat_like"] * 25 + ["legit_like"] * 2)

    reason = should_rollback(metrics, HealthPolicy(min_verdicts_before_judging=20))

    assert reason is not None
    assert "cheat-like ratio" in reason


def test_a_healthy_model_is_left_alone() -> None:
    metrics = Metrics()
    for _ in range(10):
        metrics.record_request()
    metrics.record_verdicts(["cheat_like"] * 5 + ["legit_like"] * 30)

    assert should_rollback(metrics, HealthPolicy(min_verdicts_before_judging=20)) is None
