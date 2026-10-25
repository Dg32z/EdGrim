"""The scheduled cycle: purge, judge, retrain, publish.

One pass, in that order, and the order is the design:

1. **Purge** expired samples first, so training never sees data the retention policy has already promised to
   forget.
2. **Judge** the model that is currently answering. If it is misbehaving, take it back and *stop* — a cycle
   that rolled back and then immediately published something new would have undone its own rollback.
3. **Retrain** only when the dataset has actually changed. The dataset id is content-derived, so this is a
   string comparison, not a heuristic: unchanged data means retraining would produce a byte-identical model
   with the same id, and publishing it again would only churn the history.

The cycle is driven by an explicit call rather than an internal timer, so a test can run a hundred cycles
without waiting a hundred minutes.
"""

from __future__ import annotations

from dataclasses import dataclass, field

from app.dataset import build_dataset
from app.metrics import Metrics
from app.publish import HealthPolicy, ModelPublisher, should_rollback
from app.store import SampleStore
from app.train import Trainer, TrainedModel, train_model


@dataclass
class CycleOutcome:
    """What one pass did, in enough detail to log and to assert on."""

    purged: int = 0
    published: TrainedModel | None = None
    rolled_back: str | None = None
    rollback_reason: str | None = None
    skipped: str | None = None
    datasets_considered: int = 0

    @property
    def did_anything(self) -> bool:
        return self.published is not None or self.rolled_back is not None or self.purged > 0


@dataclass
class OpsCycle:
    store: SampleStore
    publisher: ModelPublisher
    trainer: Trainer
    metrics: Metrics
    policy: HealthPolicy = field(default_factory=HealthPolicy)
    #: Called with the new model whenever the published pointer moves, so serving follows publication.
    on_model_changed: object = None

    def run_once(self, tenants: list[str]) -> CycleOutcome:
        outcome = CycleOutcome()

        outcome.purged = self.store.purge_expired()

        reason = should_rollback(self.metrics, self.policy)
        if reason is not None:
            return self._roll_back(outcome, reason)

        outcome.datasets_considered = len(tenants)
        return self._retrain(outcome, tenants)

    def _roll_back(self, outcome: CycleOutcome, reason: str) -> CycleOutcome:
        current = self.publisher.current()
        restored = self.publisher.rollback()

        if restored is None:
            # Nothing to go back to. Leaving the bad model in place is still better than having none: an
            # imperfect model that answers beats a service that stops answering.
            outcome.skipped = f"wanted to roll back ({reason}) but no earlier model is retained"
            return outcome

        outcome.rolled_back = current.model_id if current is not None else "unknown"
        outcome.rollback_reason = reason

        self.metrics.record_rollback()
        self.metrics.record_publish(restored.model_id, restored.dataset_id)
        self._notify(restored)
        return outcome

    def _retrain(self, outcome: CycleOutcome, tenants: list[str]) -> CycleOutcome:
        current = self.publisher.current()

        for tenant in tenants:
            dataset = build_dataset(self.store, tenant)
            if not dataset.is_trainable():
                outcome.skipped = "no dataset with both classes yet"
                continue

            if current is not None and dataset.dataset_id == current.dataset_id:
                outcome.skipped = "dataset unchanged since the published model"
                continue

            model = train_model(dataset, self.trainer)
            self.publisher.publish(model)
            self.metrics.record_publish(model.model_id, model.dataset_id)
            self._notify(model)
            outcome.published = model
            return outcome

        if outcome.skipped is None:
            outcome.skipped = "no trainable dataset"
        return outcome

    def _notify(self, model: TrainedModel) -> None:
        if callable(self.on_model_changed):
            self.on_model_changed(model)
