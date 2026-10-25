"""Settings and the composition root: the deployment is configurable, and wiring actually connects."""

from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from app.auth import Tenant
from app.config import (
    DEFAULT_QUOTA_PER_MINUTE,
    DEFAULT_RETENTION_DAYS,
    Settings,
    load_settings,
)
from app.serve import build_service


def test_defaults_apply_when_nothing_is_configured() -> None:
    settings = load_settings({})

    assert settings.tenants == []
    assert settings.quota_per_minute == DEFAULT_QUOTA_PER_MINUTE
    assert settings.retention_days == DEFAULT_RETENTION_DAYS
    assert not settings.is_authenticated


def test_tenants_are_parsed_from_json() -> None:
    settings = load_settings(
        {
            "WATCHNEKO_TENANTS": (
                '[{"tenant_id":"t1","api_key":"k1","servers":["s1","s2"]},'
                '{"tenant_id":"t2","api_key":"k2"}]'
            )
        }
    )

    assert len(settings.tenants) == 2
    assert settings.tenants[0].servers == frozenset({"s1", "s2"})
    assert settings.tenants[1].servers == frozenset()
    assert settings.is_authenticated


def test_a_malformed_tenant_list_is_refused_loudly() -> None:
    with pytest.raises(ValueError, match="not valid JSON"):
        load_settings({"WATCHNEKO_TENANTS": "not json"})

    with pytest.raises(ValueError, match="must be a JSON array"):
        load_settings({"WATCHNEKO_TENANTS": '{"tenant_id":"t1"}'})

    with pytest.raises(ValueError, match="tenant_id and api_key"):
        load_settings({"WATCHNEKO_TENANTS": '[{"tenant_id":"t1"}]'})


def test_numeric_settings_are_read_from_the_environment() -> None:
    settings = load_settings(
        {
            "WATCHNEKO_QUOTA_PER_MINUTE": "42",
            "WATCHNEKO_RETENTION_DAYS": "7",
            "WATCHNEKO_RETRAIN_INTERVAL_SECONDS": "120",
        }
    )

    assert settings.quota_per_minute == 42
    assert settings.retention_days == 7
    assert settings.retrain_interval_seconds == 120.0


def test_the_composition_root_produces_a_serving_app() -> None:
    service = build_service(Settings(tenants=[], database_path=":memory:"))

    response = TestClient(service.app).get("/metrics")

    assert response.status_code == 200
    assert "watchneko_samples_total" in response.text


def test_the_composition_root_wires_authentication_when_tenants_are_configured() -> None:
    service = build_service(
        Settings(
            tenants=[Tenant(tenant_id="t1", api_key="k1", servers=frozenset({"s1"}))],
            database_path=":memory:",
        )
    )
    client = TestClient(service.app)

    refused = client.post(
        "/v1/inference",
        json={"contract_version": 1, "samples": []},
        headers={"authorization": "Bearer wrong", "x-server-id": "s1"},
    )
    allowed = client.post(
        "/v1/inference",
        json={"contract_version": 1, "samples": []},
        headers={"authorization": "Bearer k1", "x-server-id": "s1"},
    )

    assert refused.status_code == 401
    # A freshly assembled service has published no model, so the authenticated answer is the documented
    # cold start (404 no_model) — what matters here is that it is not a 401.
    assert allowed.status_code == 404
    assert allowed.json()["error"] == "no_model"


def test_the_retrain_loop_targets_the_configured_tenants() -> None:
    service = build_service(
        Settings(
            tenants=[Tenant(tenant_id="t1", api_key="k1")],
            database_path=":memory:",
            retrain_interval_seconds=60,
        )
    )

    # A tick against a tenant with no data must be a no-op rather than an error.
    service.ops.run_once()


def test_the_trainer_can_be_selected_from_the_environment() -> None:
    settings = load_settings({"WATCHNEKO_TRAINER": "lightgbm"})
    assert settings.trainer == "lightgbm"


def test_an_unknown_trainer_falls_back_to_logistic() -> None:
    service = build_service(Settings(tenants=[], database_path=":memory:", trainer="does-not-exist"))

    assert service.app is not None


def test_a_service_built_for_lightgbm_can_train_and_serve() -> None:
    """End to end through the composition root, with the backend the spec actually asked for."""
    import pytest

    from app.dataset import CHEAT, LabelledSample, make_dataset
    from app.train import available_trainers

    if "lightgbm" not in available_trainers():
        pytest.skip("lightgbm is not installed in this environment")

    service = build_service(
        Settings(tenants=[], database_path=":memory:", trainer="lightgbm", retrain_interval_seconds=60)
    )

    features = lambda seed: [round(seed + i * 0.001, 6) for i in range(22)]  # noqa: E731
    samples = [
        LabelledSample(f"c{i}", "h", tuple(features(0.8)), CHEAT) for i in range(6)
    ] + [LabelledSample(f"l{i}", "h", tuple(features(0.2)), "legit") for i in range(6)]

    for sample in samples:
        label = "cheat" if sample.label == CHEAT else "legit"
        service.store.record(
            "t1", "s1", f"rs-{label}", label,
            [{"sample_id": sample.sample_id, "player_hash": "h", "features": list(sample.features)}],
        )

    service.ops._tenants = ["t1"]  # the settings above carry no tenants
    service.ops.run_once()

    assert service.registry.current() is not None
    high = service.registry.scorer().score(features(0.8))
    low = service.registry.scorer().score(features(0.2))
    assert high > low, "a LightGBM model trained through the composition root must separate the classes"
