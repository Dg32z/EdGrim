"""The composition root: everything wired together, ready to serve.

Kept separate from the app factory so the wiring is one readable place rather than a set of default
arguments a reader has to trace. This is the only module that decides *which* implementation each seam gets;
everything else takes what it is given.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass

from fastapi import FastAPI

from pathlib import Path

from app.admin_api import create_admin_router
from app.admin_auth import AdminAuth
from app.config import Settings
from app.console import create_console_router, template_directory
from app.main import create_app
from app.metrics import Metrics
from app.model import ModelRegistry
from app.ops import OpsCycle
from app.publish import ModelPublisher
from app.quota import QuotaLimiter
from app.scheduler import PeriodicOps, apply_to_registry
from app.store import SampleStore
from app.tenants import TenantStore
from app.train import available_trainers

logger = logging.getLogger(__name__)


@dataclass
class Service:
    app: FastAPI
    registry: ModelRegistry
    metrics: Metrics
    store: SampleStore
    tenants: TenantStore
    ops: PeriodicOps
    admin: AdminAuth | None


def build_service(settings: Settings, trainer_name: str | None = None) -> Service:
    """Assemble the service from settings.

    The tenant list being empty is not an error here — it is the development posture, and `create_app`
    documents what that means. A deployment serving other people configures it.
    """
    store = SampleStore(path=settings.database_path, retention_days=settings.retention_days)
    tenants = TenantStore(path=settings.database_path)
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

    # The quota ceiling comes from the tenant's own row, so the console can change one tenant's limit
    # without touching anyone else's.
    def limit_for(tenant_id: str) -> int | None:
        record = tenants.get(tenant_id)
        return None if record is None else record.quota_per_minute

    admin_auth = None
    admin_router = None
    console_router = None
    assets_directory = None
    if settings.has_admin_password:
        admin_auth = AdminAuth(
            username=settings.admin_username,
            password=settings.admin_password,
            secret=settings.admin_secret or None,
            session_ttl_seconds=settings.session_hours * 3600,
        )
        admin_router = create_admin_router(
            auth=admin_auth,
            tenants=tenants,
            metrics=metrics,
            store=store,
            publisher=cycle.publisher,
            registry=registry,
            ops=cycle,
        )
        pages = template_directory()
        if pages is not None:
            console_router = create_console_router(auth=admin_auth, template_directory=pages)
        assets = Path(__file__).parent / "static"
        if assets.is_dir():
            assets_directory = str(assets)

    app = create_app(
        registry=registry,
        store=store,
        quota=QuotaLimiter(
            samples_per_minute=settings.quota_per_minute, limit_resolver=limit_for
        ),
        metrics=metrics,
        tenant_store=tenants,
        admin_router=admin_router,
        console_router=console_router,
        assets_directory=assets_directory,
    )

    ops = PeriodicOps(
        cycle=cycle,
        tenants=lambda: [record.tenant_id for record in tenants.list() if not record.is_revoked],
        interval_seconds=settings.retrain_interval_seconds,
        metrics=metrics,
    )

    return Service(
        app=app,
        registry=registry,
        metrics=metrics,
        store=store,
        tenants=tenants,
        ops=ops,
        admin=admin_auth,
    )


def build_app(settings: Settings, trainer_name: str | None = None) -> FastAPI:
    """The application alone, for `uvicorn --factory`."""
    return build_service(settings, trainer_name).app
