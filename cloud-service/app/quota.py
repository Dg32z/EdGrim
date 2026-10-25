"""Per-tenant quota, counted in samples rather than requests.

Counting samples rather than requests is the whole design decision here: the plugin batches, so request
count is a function of the batch size the caller chose, not of how much data they are actually pushing. A
tenant who lowers their batch size would otherwise get proportionally more throughput out of the same
quota, which is exactly backwards.

The window slides, so a tenant who goes quiet regains capacity continuously instead of waiting for a
calendar minute to tick over.
"""

from __future__ import annotations

import time
from collections import deque
from typing import Callable


class QuotaLimiter:
    """A sliding one-minute window of accepted samples, per tenant."""

    def __init__(
        self,
        samples_per_minute: int,
        window_seconds: float = 60.0,
        limit_resolver: Callable[[str], int | None] | None = None,
    ) -> None:
        if samples_per_minute < 0:
            raise ValueError("samples_per_minute must not be negative")
        if window_seconds <= 0:
            raise ValueError("window_seconds must be positive")
        self._limit = samples_per_minute
        self._window = window_seconds
        self._limit_resolver = limit_resolver
        self._usage: dict[str, deque[tuple[float, int]]] = {}

    def limit_for(self, tenant_id: str) -> int | None:
        """This tenant's ceiling, or ``None`` when it has none.

        A quota of ``0`` means **unlimited**, at either level: a tenant configured with 0 is uncapped, and a
        default of 0 makes every tenant without its own quota uncapped. Zero is a meaningful setting rather
        than a missing one, which is why the resolver's ``None`` (no tenant row) stays distinct from its
        ``0`` (a tenant that asked for no limit).
        """
        if self._limit_resolver is not None:
            resolved = self._limit_resolver(tenant_id)
            if resolved is not None:
                return None if resolved <= 0 else resolved
        return None if self._limit <= 0 else self._limit

    @property
    def limit(self) -> int:
        """The default ceiling. ``0`` means unlimited."""
        return self._limit

    def used(self, tenant_id: str, now: float | None = None) -> int:
        """Samples this tenant has spent inside the current window."""
        self._evict(tenant_id, now if now is not None else time.monotonic())
        return sum(count for _, count in self._usage.get(tenant_id, ()))

    def try_consume(self, tenant_id: str, count: int, now: float | None = None) -> bool:
        """Spend ``count`` samples of quota. Returns False and spends nothing when it would overflow.

        All-or-nothing on purpose: partially accepting a batch would make the plugin's accounting
        disagree with ours about what it has left.
        """
        if count < 0:
            raise ValueError("count must not be negative")

        stamp = now if now is not None else time.monotonic()
        self._evict(tenant_id, stamp)

        limit = self.limit_for(tenant_id)
        if limit is not None and self.used(tenant_id, stamp) + count > limit:
            return False

        if count:
            self._usage.setdefault(tenant_id, deque()).append((stamp, count))
        return True

    def _evict(self, tenant_id: str, now: float) -> None:
        usage = self._usage.get(tenant_id)
        if not usage:
            return
        cutoff = now - self._window
        while usage and usage[0][0] <= cutoff:
            usage.popleft()
        if not usage:
            self._usage.pop(tenant_id, None)
