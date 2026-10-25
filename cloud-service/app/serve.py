"""The composition root: everything wired together, ready to serve.

Kept separate from the app factory so the wiring is one readable place rather than a set of default
arguments a reader has to trace. This is the only module that decides *which* implementation each seam gets;
everything else takes what it is given.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass

from fastapi import FastAPI

from app.config import Settings
from app.main import create_app
from app.metrics import Metrics
from app.model import ModelRegistry
from app.ops import OpsCycle
from app.publish import ModelPublisher
from app.quota import QuotaLimiter
from app.scheduler import PeriodicOps, apply_to_registry
from app.store import SampleStore
from app.train import available_trainers

logger = logging.getLogger(__name__)


@dataclass
class Service:
    app: FastAPI
    registry: ModelRegistry
    metrics: Metrics
    store: SampleStore
    ops: PeriodicOps


def build_service(settings: Settings, trainer_name: str | None = None) -> Service:
    """Assemble the service from settings.

    The tenant list being empty is not an error here — it is the development posture, and `create_app`
    documents what that means. A deployment serving other people configures it.
    """
    store = SampleStore(path=settings.database_path, retention_days=settings.retention_days)
    metrics = Metrics()
    registry = ModelRegistry()

    trainers = available_trainers()
    requested = trainer_name or settings.trainer
    if requested not in trainers:
        # Falling back silently would make "which backend trained this?" unanswerable from the outside.
        logger.warning("trainer %r unavailable; falling back to logistic", requested)
        requested = "logistic"

    cycle = OpsCycle(
        store=store,
        publisher=ModelPublisher(),
        trainer=trainers[requested],
        metrics=metrics,
        on_model_changed=apply_to_registry(registry),
    )

    app = create_app(
        registry=registry,
        store=store,
        quota=QuotaLimiter(samples_per_minute=settings.quota_per_minute),
        metrics=metrics,
        tenants=settings.tenants or None,
    )

    ops = PeriodicOps(
        cycle=cycle,
        tenants=[tenant.tenant_id for tenant in settings.tenants],
        interval_seconds=settings.retrain_interval_seconds,
        metrics=metrics,
    )

    return Service(app=app, registry=registry, metrics=metrics, store=store, ops=ops)


def build_app(settings: Settings, trainer_name: str | None = None) -> FastAPI:
    """The application alone, for `uvicorn --factory`."""
    return build_service(settings, trainer_name).app
