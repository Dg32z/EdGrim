"""Publishing models, and taking them back.

Two ideas, both deliberate:

**A model is published, never edited.** Publishing appends to a history and moves the pointer; rolling back
moves the pointer back. Nothing is deleted, so the previous version keeps answering exactly as it did — which
is the whole reason a rollback is safe.

**Rollback is automatic and evidence-driven.** ADR-0004 chose auto-publish over a human gate, which leaves
the health check as the only brake. It therefore reads the metrics the service already exports rather than a
separate notion of health, so what triggers a rollback is exactly what an operator can see.
"""

from __future__ import annotations

from dataclasses import dataclass, field

from app.metrics import Metrics
from app.train import TrainedModel


@dataclass(frozen=True)
class HealthPolicy:
    """When a published model has to be taken back.

    The two bounds are deliberately generous. A rollback is a blunt instrument — it takes the new model away
    from everyone — so it should fire on a model that is clearly misbehaving, not on normal variance.
    """

    max_error_rate: float = 0.25
    max_cheat_ratio: float = 0.9
    min_verdicts_before_judging: int = 20


@dataclass
class PublishRecord:
    model: TrainedModel
    rolled_back: bool = False


@dataclass
class ModelPublisher:
    """The published model history, newest last, with at least two versions retained."""

    history: list[PublishRecord] = field(default_factory=list)

    def publish(self, model: TrainedModel) -> PublishRecord:
        record = PublishRecord(model=model)
        self.history.append(record)
        return record

    def current(self) -> TrainedModel | None:
        for record in reversed(self.history):
            if not record.rolled_back:
                return record.model
        return None

    def previous(self) -> TrainedModel | None:
        live = [record for record in self.history if not record.rolled_back]
        return live[-2].model if len(live) >= 2 else None

    def retained_versions(self) -> int:
        """How many models can still answer. Must stay at or above two to make rollback meaningful."""
        return len([record for record in self.history if not record.rolled_back])

    def rollback(self) -> TrainedModel | None:
        """Retire the newest live model and hand back the one before it.

        Returns ``None`` when there is nothing to roll back to, in which case the pointer is untouched: a
        rollback that lands on no model would take detection offline, which is worse than a bad model.
        """
        live = [record for record in self.history if not record.rolled_back]
        if len(live) < 2:
            return None

        live[-1].rolled_back = True
        return live[-2].model


def should_rollback(metrics: Metrics, policy: HealthPolicy) -> str | None:
    """The reason this model should be taken back, or ``None`` when it is behaving.

    Returns a human-readable reason so the rollback can be logged with the ``model_id`` that caused it — the
    audit trail ADR-0004 depends on.
    """
    if metrics.total_verdicts < policy.min_verdicts_before_judging:
        return None

    if metrics.error_rate > policy.max_error_rate:
        return f"error rate {metrics.error_rate:.3f} above {policy.max_error_rate}"

    cheat_ratio = metrics.cheat_ratio()
    if cheat_ratio > policy.max_cheat_ratio:
        return f"cheat-like ratio {cheat_ratio:.3f} above {policy.max_cheat_ratio}"

    return None
