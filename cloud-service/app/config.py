"""Deployment settings, read from the environment.

Everything the service needs to be configured lives here, in one place, with a default for every value so
that a bare `docker compose up` produces a working (if unauthenticated) service rather than a stack trace
about a missing variable.

Tenants are JSON rather than a delimited string because a tenant carries a list of servers, and every
delimiter scheme for that ends up needing an escape character.
"""

from __future__ import annotations

import json
import os
from dataclasses import dataclass, field

from app.auth import Tenant

DEFAULT_QUOTA_PER_MINUTE = 6000
DEFAULT_RETENTION_DAYS = 90
DEFAULT_RETRAIN_INTERVAL_SECONDS = 3600


@dataclass(frozen=True)
class Settings:
    tenants: list[Tenant] = field(default_factory=list)
    quota_per_minute: int = DEFAULT_QUOTA_PER_MINUTE
    retention_days: int = DEFAULT_RETENTION_DAYS
    retrain_interval_seconds: float = DEFAULT_RETRAIN_INTERVAL_SECONDS
    database_path: str = "watchneko.db"

    @property
    def is_authenticated(self) -> bool:
        return bool(self.tenants)


def _parse_tenants(raw: str | None) -> list[Tenant]:
    if not raw:
        return []
    try:
        entries = json.loads(raw)
    except json.JSONDecodeError as error:
        raise ValueError(f"WATCHNEKO_TENANTS is not valid JSON: {error}") from error

    if not isinstance(entries, list):
        raise ValueError("WATCHNEKO_TENANTS must be a JSON array")

    tenants = []
    for entry in entries:
        if not isinstance(entry, dict) or "tenant_id" not in entry or "api_key" not in entry:
            raise ValueError("each tenant needs at least tenant_id and api_key")
        tenants.append(
            Tenant(
                tenant_id=str(entry["tenant_id"]),
                api_key=str(entry["api_key"]),
                servers=frozenset(str(server) for server in entry.get("servers", [])),
            )
        )
    return tenants


def load_settings(environ: dict[str, str] | None = None) -> Settings:
    source = environ if environ is not None else os.environ

    return Settings(
        tenants=_parse_tenants(source.get("WATCHNEKO_TENANTS")),
        quota_per_minute=int(source.get("WATCHNEKO_QUOTA_PER_MINUTE", DEFAULT_QUOTA_PER_MINUTE)),
        retention_days=int(source.get("WATCHNEKO_RETENTION_DAYS", DEFAULT_RETENTION_DAYS)),
        retrain_interval_seconds=float(
            source.get("WATCHNEKO_RETRAIN_INTERVAL_SECONDS", DEFAULT_RETRAIN_INTERVAL_SECONDS)
        ),
        database_path=source.get("WATCHNEKO_DATABASE_PATH", "watchneko.db"),
    )
