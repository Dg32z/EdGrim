"""Storage for labelled samples, and the retention rules that govern them.

Two tables: a recording session is the batch an admin uploaded in one go, and a sample belongs to exactly
one session. Keeping the session as a row is what makes an upload traceable — "which recording did this
sample come from" is a question that gets asked the moment a model starts behaving oddly.

Samples are stored per tenant. The player id is a hash the plugin computed under the tenant's salt, so
deleting a player's data is a well-defined operation and cross-tenant correlation is impossible.
"""

from __future__ import annotations

import json
import sqlite3
from datetime import datetime, timedelta, timezone

DEFAULT_RETENTION_DAYS = 90

_SCHEMA = """
CREATE TABLE IF NOT EXISTS recording_sessions (
    tenant_id TEXT NOT NULL,
    session_id TEXT NOT NULL,
    server_id TEXT NOT NULL,
    label TEXT NOT NULL,
    created_at TEXT NOT NULL,
    accepted INTEGER NOT NULL,
    rejected INTEGER NOT NULL,
    PRIMARY KEY (tenant_id, session_id)
);

CREATE TABLE IF NOT EXISTS samples (
    tenant_id TEXT NOT NULL,
    sample_id TEXT NOT NULL,
    session_id TEXT NOT NULL,
    player_hash TEXT NOT NULL,
    features TEXT NOT NULL,
    recorded_at TEXT NOT NULL,
    PRIMARY KEY (tenant_id, sample_id)
);

CREATE INDEX IF NOT EXISTS samples_by_player ON samples (tenant_id, player_hash);
CREATE INDEX IF NOT EXISTS samples_by_time ON samples (recorded_at);
"""


class SampleStore:
    """Labelled samples in SQLite, scoped to a tenant."""

    def __init__(self, path: str = ":memory:", retention_days: int = DEFAULT_RETENTION_DAYS) -> None:
        if retention_days < 1:
            raise ValueError("retention_days must be positive")
        self._connection = sqlite3.connect(path, check_same_thread=False)
        self._connection.executescript(_SCHEMA)
        self._retention_days = retention_days

    @property
    def retention_days(self) -> int:
        return self._retention_days

    def record(
        self,
        tenant_id: str,
        server_id: str,
        session_id: str,
        label: str,
        samples: list[dict],
        now: datetime | None = None,
    ) -> tuple[int, int]:
        """Store one uploaded batch. Returns ``(accepted, rejected)``.

        A sample whose id this tenant already has is rejected rather than overwritten: a replayed upload is
        a bug in the caller, and silently accepting it would inflate the dataset with duplicates.
        """
        stamp = (now or datetime.now(timezone.utc)).isoformat()
        accepted = 0
        rejected = 0

        with self._connection:
            for sample in samples:
                cursor = self._connection.execute(
                    "INSERT OR IGNORE INTO samples"
                    " (tenant_id, sample_id, session_id, player_hash, features, recorded_at)"
                    " VALUES (?, ?, ?, ?, ?, ?)",
                    (
                        tenant_id,
                        sample["sample_id"],
                        session_id,
                        sample["player_hash"],
                        json.dumps(sample["features"]),
                        stamp,
                    ),
                )
                if cursor.rowcount:
                    accepted += 1
                else:
                    rejected += 1

            self._connection.execute(
                "INSERT OR REPLACE INTO recording_sessions"
                " (tenant_id, session_id, server_id, label, created_at, accepted, rejected)"
                " VALUES (?, ?, ?, ?, ?, ?, ?)",
                (tenant_id, session_id, server_id, label, stamp, accepted, rejected),
            )

        return accepted, rejected

    def sample_count(self, tenant_id: str | None = None) -> int:
        if tenant_id is None:
            row = self._connection.execute("SELECT COUNT(*) FROM samples").fetchone()
        else:
            row = self._connection.execute(
                "SELECT COUNT(*) FROM samples WHERE tenant_id = ?", (tenant_id,)
            ).fetchone()
        return int(row[0])

    def session_label(self, tenant_id: str, session_id: str) -> str | None:
        row = self._connection.execute(
            "SELECT label FROM recording_sessions WHERE tenant_id = ? AND session_id = ?",
            (tenant_id, session_id),
        ).fetchone()
        return None if row is None else str(row[0])

    def player_hashes(self, tenant_id: str) -> list[str]:
        rows = self._connection.execute(
            "SELECT DISTINCT player_hash FROM samples WHERE tenant_id = ? ORDER BY player_hash",
            (tenant_id,),
        ).fetchall()
        return [str(row[0]) for row in rows]

    def session_ids(self, tenant_id: str) -> list[str]:
        """Every recording session this tenant has uploaded, oldest first."""
        rows = self._connection.execute(
            "SELECT session_id FROM recording_sessions WHERE tenant_id = ? ORDER BY created_at, session_id",
            (tenant_id,),
        ).fetchall()
        return [str(row[0]) for row in rows]

    def samples_of_session(
        self, tenant_id: str, session_id: str
    ) -> list[tuple[str, str, list[float]]]:
        """The samples of one session as ``(sample_id, player_hash, features)``."""
        rows = self._connection.execute(
            "SELECT sample_id, player_hash, features FROM samples"
            " WHERE tenant_id = ? AND session_id = ? ORDER BY sample_id",
            (tenant_id, session_id),
        ).fetchall()
        return [(str(row[0]), str(row[1]), json.loads(row[2])) for row in rows]

    def delete_player(self, tenant_id: str, player_hash: str) -> int:
        """Delete every sample belonging to one player. Returns how many rows went."""
        with self._connection:
            cursor = self._connection.execute(
                "DELETE FROM samples WHERE tenant_id = ? AND player_hash = ?",
                (tenant_id, player_hash),
            )
        return int(cursor.rowcount)

    def purge_expired(self, now: datetime | None = None) -> int:
        """Drop samples past the retention window. Returns how many rows went."""
        cutoff = (now or datetime.now(timezone.utc)) - timedelta(days=self._retention_days)
        with self._connection:
            cursor = self._connection.execute(
                "DELETE FROM samples WHERE recorded_at < ?", (cutoff.isoformat(),)
            )
        return int(cursor.rowcount)

    def close(self) -> None:
        self._connection.close()
