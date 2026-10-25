"""Tenants, persisted, with API keys that are never stored in the clear.

This replaces the previous arrangement where tenants existed only as a JSON blob in an environment
variable. Three things become possible that were not before, and they are the reason for the table:

* **Rotation.** A key can be replaced without editing a deployment file and restarting the process.
* **Revocation.** One tenant can be cut off without deleting its data or touching anyone else's.
* **Audit.** When a key was created, when it was last used, when it was revoked.

Keys are high-entropy random tokens, so a plain SHA-256 digest is the right storage: there is nothing to
brute-force, and unlike a password hash it can be recomputed cheaply on every request. The plaintext is
returned exactly once, at creation or rotation, and never again — which is why the UI has to show it in a
dialog rather than a table cell.
"""

from __future__ import annotations

import hashlib
import secrets
import sqlite3
from dataclasses import dataclass
from datetime import datetime, timezone

KEY_PREFIX_LENGTH = 8
#: 0 means unlimited; the default is a real ceiling so a misconfigured tenant cannot flood the service.
DEFAULT_QUOTA_PER_MINUTE = 6000

_SCHEMA = """
CREATE TABLE IF NOT EXISTS tenants (
    tenant_id TEXT PRIMARY KEY,
    api_key_hash TEXT NOT NULL UNIQUE,
    api_key_prefix TEXT NOT NULL,
    servers TEXT NOT NULL,
    quota_per_minute INTEGER NOT NULL,
    created_at TEXT NOT NULL,
    revoked_at TEXT,
    last_used_at TEXT
);
"""


@dataclass(frozen=True)
class TenantRecord:
    tenant_id: str
    api_key_prefix: str
    servers: tuple[str, ...]
    quota_per_minute: int
    created_at: str
    revoked_at: str | None
    last_used_at: str | None

    @property
    def is_revoked(self) -> bool:
        return self.revoked_at is not None


class TenantStore:
    def __init__(self, path: str = ":memory:") -> None:
        self._connection = sqlite3.connect(path, check_same_thread=False)
        self._connection.executescript(_SCHEMA)

    # -- reads -----------------------------------------------------------------------------------

    def list(self) -> list[TenantRecord]:
        rows = self._connection.execute(
            "SELECT tenant_id, api_key_prefix, servers, quota_per_minute, created_at,"
            " revoked_at, last_used_at FROM tenants ORDER BY created_at DESC"
        ).fetchall()
        return [self._record(row) for row in rows]

    def get(self, tenant_id: str) -> TenantRecord | None:
        row = self._connection.execute(
            "SELECT tenant_id, api_key_prefix, servers, quota_per_minute, created_at,"
            " revoked_at, last_used_at FROM tenants WHERE tenant_id = ?",
            (tenant_id,),
        ).fetchone()
        return None if row is None else self._record(row)

    # -- writes ----------------------------------------------------------------------------------

    def create(
        self,
        tenant_id: str,
        servers: list[str] | None = None,
        quota_per_minute: int = DEFAULT_QUOTA_PER_MINUTE,
    ) -> tuple[TenantRecord, str]:
        """Create a tenant and return it with its plaintext key, which is the only time it exists."""
        if not tenant_id or not tenant_id.strip():
            raise ValueError("tenant_id must not be blank")
        if quota_per_minute < 0:
            raise ValueError("quota_per_minute must not be negative")
        if self.get(tenant_id) is not None:
            raise ValueError(f"tenant {tenant_id!r} already exists")

        key = _new_key()
        with self._connection:
            self._connection.execute(
                "INSERT INTO tenants (tenant_id, api_key_hash, api_key_prefix, servers,"
                " quota_per_minute, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                (
                    tenant_id,
                    _digest(key),
                    key[:KEY_PREFIX_LENGTH],
                    _join(servers),
                    quota_per_minute,
                    _now(),
                ),
            )
        return self.get(tenant_id), key

    def rotate(self, tenant_id: str) -> tuple[TenantRecord, str]:
        """Replace a tenant's key. The old one stops working immediately — there is no overlap."""
        record = self.get(tenant_id)
        if record is None:
            raise KeyError(tenant_id)
        if record.is_revoked:
            raise ValueError("cannot rotate the key of a revoked tenant")

        key = _new_key()
        with self._connection:
            self._connection.execute(
                "UPDATE tenants SET api_key_hash = ?, api_key_prefix = ? WHERE tenant_id = ?",
                (_digest(key), key[:KEY_PREFIX_LENGTH], tenant_id),
            )
        return self.get(tenant_id), key

    def update(
        self,
        tenant_id: str,
        servers: list[str] | None = None,
        quota_per_minute: int | None = None,
    ) -> TenantRecord:
        record = self.get(tenant_id)
        if record is None:
            raise KeyError(tenant_id)

        new_servers = record.servers if servers is None else tuple(servers)
        new_quota = record.quota_per_minute if quota_per_minute is None else quota_per_minute
        if new_quota < 0:
            raise ValueError("quota_per_minute must not be negative")

        with self._connection:
            self._connection.execute(
                "UPDATE tenants SET servers = ?, quota_per_minute = ? WHERE tenant_id = ?",
                (_join(list(new_servers)), new_quota, tenant_id),
            )
        return self.get(tenant_id)

    def revoke(self, tenant_id: str) -> TenantRecord:
        """Cut a tenant off. Its samples stay put: revocation is about access, not about erasure."""
        if self.get(tenant_id) is None:
            raise KeyError(tenant_id)
        with self._connection:
            self._connection.execute(
                "UPDATE tenants SET revoked_at = ? WHERE tenant_id = ? AND revoked_at IS NULL",
                (_now(), tenant_id),
            )
        return self.get(tenant_id)

    def delete(self, tenant_id: str) -> None:
        with self._connection:
            self._connection.execute("DELETE FROM tenants WHERE tenant_id = ?", (tenant_id,))

    # -- authentication --------------------------------------------------------------------------

    def authenticate(self, api_key: str, server_id: str) -> str | None:
        """The tenant behind these credentials, or ``None``.

        Returns ``None`` for a revoked tenant as well as an unknown key: a revoked key is not a
        distinguishable state to an unauthenticated caller.
        """
        row = self._connection.execute(
            "SELECT tenant_id, servers, revoked_at FROM tenants WHERE api_key_hash = ?",
            (_digest(api_key),),
        ).fetchone()
        if row is None or row[2] is not None:
            return None

        servers = _split(row[1])
        if servers and server_id not in servers:
            return None

        self._touch(row[0])
        return str(row[0])

    def _touch(self, tenant_id: str) -> None:
        with self._connection:
            self._connection.execute(
                "UPDATE tenants SET last_used_at = ? WHERE tenant_id = ?", (_now(), tenant_id)
            )

    @staticmethod
    def _record(row) -> TenantRecord:
        return TenantRecord(
            tenant_id=str(row[0]),
            api_key_prefix=str(row[1]),
            servers=tuple(_split(row[2])),
            quota_per_minute=int(row[3]),
            created_at=str(row[4]),
            revoked_at=None if row[5] is None else str(row[5]),
            last_used_at=None if row[6] is None else str(row[6]),
        )

    def close(self) -> None:
        self._connection.close()


def _new_key() -> str:
    return "wnk_" + secrets.token_urlsafe(32)


def _digest(key: str) -> str:
    return hashlib.sha256(key.encode("utf-8")).hexdigest()


def _join(servers: list[str] | None) -> str:
    return ",".join(sorted(server.strip() for server in (servers or []) if server.strip()))


def _split(raw: str | None) -> list[str]:
    return [] if not raw else [part for part in str(raw).split(",") if part]


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()
