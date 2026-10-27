"""The thing that makes the cycle periodic, and that keeps serving in step with publication.

A plain daemon thread rather than an async task: the work is CPU-bound training, and putting it on the event
loop would stall inference for the duration of a fit. Running it off-loop means a retrain costs latency
nowhere.

Two behaviours are deliberately small but load-bearing:

* **It survives its own failures.** A training run that throws must not kill the scheduler, or the service
  silently stops retraining and nobody notices for a week. The exception is recorded as an error metric and
  the next tick tries again.
* **Serving follows publication.** The registry is updated through the same callback the cycle uses, so the
  model answering requests is always the one the metrics name.
"""

from __future__ import annotations

import logging
import threading
import time
from typing import Callable

from app.metrics import Metrics
from app.model import Model, ModelRegistry
from app.ops import OpsCycle
from app.train import TrainedModel, predictor_from_artifact

logger = logging.getLogger(__name__)


def apply_to_registry(registry: ModelRegistry) -> Callable[[TrainedModel], None]:
    """A publication callback that makes the registry serve the newly published model."""

    def apply(model: TrainedModel) -> None:
        try:
            scorer = predictor_from_artifact(model.artifact)
        except Exception:
            # The backend published a model this build cannot serve — an unknown artifact kind, or a
            # corrupt booster string. Leaving the previous model answering beats taking detection offline,
            # and the failure is visible in the metrics rather than silent.
            logger.exception("cannot serve model %s; keeping the previous model", model.model_id)
            return
        registry.set(
            Model(
                model_id=model.model_id,
                dataset_id=model.dataset_id,
                thresholds=model.thresholds,
                feature_dimensions=model.feature_dimensions,
            ),
            scorer,
        )

    return apply


class PeriodicOps:
    """Runs the cycle on an interval until stopped."""

    def __init__(
        self,
        cycle: OpsCycle,
        tenants: "list[str] | Callable[[], list[str]]",
        interval_seconds: float,
        metrics: Metrics | None = None,
    ) -> None:
        if interval_seconds <= 0:
            raise ValueError("interval_seconds must be positive")
        self._cycle = cycle
        # A callable rather than a list: tenants now live in a table the console edits, and a snapshot
        # taken at startup would keep retraining on a tenant that has since been revoked.
        self._tenants = tenants
        self._interval = interval_seconds
        self._metrics = metrics if metrics is not None else cycle.metrics
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None

    def run_once(self) -> None:
        """One tick, with its failures contained."""
        try:
            outcome = self._cycle.run_once(self._current_tenants())
            if outcome.published is not None:
                logger.info("published model %s from dataset %s",
                            outcome.published.model_id, outcome.published.dataset_id)
            if outcome.rolled_back is not None:
                logger.warning("rolled back model %s: %s",
                               outcome.rolled_back, outcome.rollback_reason)
        except Exception:
            self._metrics.record_error()
            logger.exception("retrain cycle failed; will retry next tick")

    def _current_tenants(self) -> list[str]:
        return self._tenants() if callable(self._tenants) else list(self._tenants)

    def start(self) -> None:
        if self._thread is not None:
            return
        self._thread = threading.Thread(target=self._loop, name="watchneko-ops", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()

    def _loop(self) -> None:
        # Retrain immediately on start, then on the interval.
        #
        # Models are deliberately not persisted: the published history lives in this process only, so a
        # restart begins with no model at all. Waiting a full interval first would leave the service
        # answering 404 no_model - and every plugin that talks to it running detection-disabled - for up
        # to an hour after every restart. The samples are in SQLite, so the work to recover is one cycle,
        # and there is no reason to delay it.
        self.run_once()
        while not self._stop.wait(self._interval):
            self.run_once()
