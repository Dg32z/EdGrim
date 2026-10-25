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


def test_a_non_positive_limit_is_refused() -> None:
    with pytest.raises(ValueError):
        QuotaLimiter(samples_per_minute=0)


def test_a_negative_batch_is_refused() -> None:
    with pytest.raises(ValueError):
        QuotaLimiter(samples_per_minute=10).try_consume("t1", -1)
