"""Quota: a sliding window of samples, spent all-or-nothing."""

from __future__ import annotations

import pytest

from app.quota import QuotaLimiter


def test_a_tenant_under_the_limit_spends_freely() -> None:
    quota = QuotaLimiter(samples_per_minute=100)
    assert quota.try_consume("t1", 40, now=0.0) is True
    assert quota.try_consume("t1", 40, now=1.0) is True
    assert quota.used("t1", now=1.0) == 80


def test_exceeding_the_limit_is_refused() -> None:
    quota = QuotaLimiter(samples_per_minute=100)
    quota.try_consume("t1", 90, now=0.0)

    assert quota.try_consume("t1", 20, now=1.0) is False


def test_a_refused_batch_spends_nothing() -> None:
    quota = QuotaLimiter(samples_per_minute=100)
    quota.try_consume("t1", 90, now=0.0)

    quota.try_consume("t1", 20, now=1.0)

    assert quota.used("t1", now=1.0) == 90, "a rejected batch must not partially spend quota"


def test_capacity_returns_as_the_window_slides() -> None:
    quota = QuotaLimiter(samples_per_minute=100, window_seconds=60.0)
    quota.try_consume("t1", 100, now=0.0)
    assert quota.try_consume("t1", 1, now=59.0) is False

    assert quota.try_consume("t1", 1, now=61.0) is True, "the first spend has aged out"
    assert quota.used("t1", now=61.0) == 1


def test_tenants_do_not_spend_each_others_quota() -> None:
    quota = QuotaLimiter(samples_per_minute=100)
    quota.try_consume("t1", 100, now=0.0)

    assert quota.try_consume("t2", 100, now=0.0) is True
    assert quota.used("t1", now=0.0) == 100
    assert quota.used("t2", now=0.0) == 100


def test_a_zero_sample_batch_is_allowed_and_costs_nothing() -> None:
    quota = QuotaLimiter(samples_per_minute=10)
    assert quota.try_consume("t1", 0, now=0.0) is True
    assert quota.used("t1", now=0.0) == 0


def test_a_negative_limit_is_refused() -> None:
    with pytest.raises(ValueError):
        QuotaLimiter(samples_per_minute=-1)


# -- 0 means unlimited ---------------------------------------------------------------------------


def test_a_default_of_zero_means_unlimited() -> None:
    quota = QuotaLimiter(samples_per_minute=0)

    assert quota.try_consume("t1", 10_000_000, now=0.0) is True
    assert quota.limit_for("t1") is None


def test_a_tenant_quota_of_zero_overrides_the_default_to_unlimited() -> None:
    quota = QuotaLimiter(samples_per_minute=10, limit_resolver=lambda tenant: 0)

    assert quota.try_consume("t1", 1_000, now=0.0) is True


def test_a_tenant_quota_of_none_falls_back_to_the_default() -> None:
    """No tenant row is not the same as a tenant asking for no limit."""
    quota = QuotaLimiter(samples_per_minute=10, limit_resolver=lambda tenant: None)

    assert quota.try_consume("t1", 11, now=0.0) is False
    assert quota.limit_for("t1") == 10


def test_a_tenant_quota_above_zero_still_applies() -> None:
    quota = QuotaLimiter(samples_per_minute=10, limit_resolver=lambda tenant: 100)

    assert quota.limit_for("t1") == 100
    assert quota.try_consume("t1", 100, now=0.0) is True
    assert quota.try_consume("t1", 1, now=0.0) is False


def test_an_unlimited_tenant_still_has_its_usage_counted() -> None:
    """Unlimited means never refused, not unmeasured — the dashboard reads these numbers."""
    quota = QuotaLimiter(samples_per_minute=0)

    quota.try_consume("t1", 42, now=0.0)

    assert quota.used("t1", now=0.0) == 42


def test_an_unlimited_tenant_never_recovers_capacity_because_it_never_spends_it() -> None:
    quota = QuotaLimiter(samples_per_minute=0)
    for _ in range(100):
        assert quota.try_consume("t1", 1_000, now=0.0) is True


def test_a_negative_batch_is_refused() -> None:
    with pytest.raises(ValueError):
        QuotaLimiter(samples_per_minute=10).try_consume("t1", -1)
