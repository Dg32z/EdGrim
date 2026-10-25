"""Metrics: the five families an operator reads, in the format a scraper expects."""

from __future__ import annotations

from app.metrics import Metrics


def test_the_rendered_output_declares_each_family_once() -> None:
    rendered = Metrics().render()

    for family in (
        "watchneko_samples_total",
        "watchneko_verdicts_total",
        "watchneko_model_info",
        "watchneko_error_rate",
        "watchneko_queue_backlog",
    ):
        assert f"# TYPE {family}" in rendered


def test_samples_are_broken_down_by_tenant() -> None:
    metrics = Metrics()
    metrics.record_samples("t1", 5)
    metrics.record_samples("t2", 3)

    rendered = metrics.render()

    assert 'watchneko_samples_total{tenant="t1"} 5' in rendered
    assert 'watchneko_samples_total{tenant="t2"} 3' in rendered


def test_verdicts_are_broken_down_by_conclusion() -> None:
    metrics = Metrics()
    metrics.record_verdicts(["cheat_like", "cheat_like", "legit_like"])

    rendered = metrics.render()

    assert 'watchneko_verdicts_total{verdict="cheat_like"} 2' in rendered
    assert 'watchneko_verdicts_total{verdict="legit_like"} 1' in rendered
    assert 'watchneko_verdicts_total{verdict="uncertain"} 0' in rendered


def test_all_three_verdict_labels_are_always_present() -> None:
    """A label that only appears once it happens is a label a dashboard cannot alert on."""
    rendered = Metrics().render()

    for label in ("cheat_like", "legit_like", "uncertain"):
        assert f'verdict="{label}"' in rendered


def test_the_error_rate_is_derived_not_counted() -> None:
    metrics = Metrics()
    for _ in range(4):
        metrics.record_request()
    metrics.record_error()

    assert metrics.error_rate == 0.25


def test_an_unused_service_reports_a_zero_error_rate_rather_than_dividing_by_zero() -> None:
    assert Metrics().error_rate == 0.0


def test_the_cheat_ratio_is_the_share_of_cheat_like_verdicts() -> None:
    metrics = Metrics()
    metrics.record_verdicts(["cheat_like"] * 3 + ["legit_like"])

    assert metrics.cheat_ratio() == 0.75


def test_an_unused_service_reports_a_zero_cheat_ratio() -> None:
    assert Metrics().cheat_ratio() == 0.0


def test_the_live_model_and_its_dataset_are_exported() -> None:
    metrics = Metrics()
    metrics.record_publish("m-abc", "ds-def")

    rendered = metrics.render()

    assert 'model_id="m-abc"' in rendered
    assert 'dataset_id="ds-def"' in rendered


def test_rollbacks_are_counted() -> None:
    metrics = Metrics()
    metrics.record_rollback()

    assert "watchneko_rollbacks_total 1" in metrics.render()
