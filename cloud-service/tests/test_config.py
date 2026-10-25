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
