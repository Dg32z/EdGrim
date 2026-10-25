"""Authentication: who is calling, and may they."""

from __future__ import annotations

import pytest
from fastapi import HTTPException

from app.auth import Tenant, TenantRegistry

SURVIVAL = Tenant(tenant_id="t-survival", api_key="key-survival", servers=frozenset({"survival-1"}))
CREATIVE = Tenant(tenant_id="t-creative", api_key="key-creative", servers=frozenset())


@pytest.fixture
def registry() -> TenantRegistry:
    return TenantRegistry([SURVIVAL, CREATIVE])


def test_a_known_key_and_its_server_identifies_the_tenant(registry: TenantRegistry) -> None:
    assert registry.authenticate("Bearer key-survival", "survival-1") == "t-survival"


def test_the_scheme_is_case_insensitive(registry: TenantRegistry) -> None:
    assert registry.authenticate("bearer key-survival", "survival-1") == "t-survival"


def test_a_tenant_with_no_declared_servers_accepts_any_server(registry: TenantRegistry) -> None:
    assert registry.authenticate("Bearer key-creative", "anything") == "t-creative"


def test_an_unknown_key_is_unauthenticated(registry: TenantRegistry) -> None:
    with pytest.raises(HTTPException) as failure:
        registry.authenticate("Bearer nope", "survival-1")
    assert failure.value.status_code == 401


def test_a_missing_header_is_unauthenticated(registry: TenantRegistry) -> None:
    with pytest.raises(HTTPException) as failure:
        registry.authenticate(None, "survival-1")
    assert failure.value.status_code == 401


def test_a_malformed_header_is_unauthenticated(registry: TenantRegistry) -> None:
    with pytest.raises(HTTPException) as failure:
        registry.authenticate("key-survival", "survival-1")
    assert failure.value.status_code == 401


def test_a_server_outside_the_tenant_is_forbidden_not_unauthenticated(
    registry: TenantRegistry,
) -> None:
    """403, not 401: we know who is calling, they just may not use this server id."""
    with pytest.raises(HTTPException) as failure:
        registry.authenticate("Bearer key-survival", "someone-elses-server")
    assert failure.value.status_code == 403


def test_a_missing_server_id_is_unauthenticated(registry: TenantRegistry) -> None:
    with pytest.raises(HTTPException) as failure:
        registry.authenticate("Bearer key-survival", None)
    assert failure.value.status_code == 401


def test_an_empty_registry_authenticates_nobody() -> None:
    with pytest.raises(HTTPException):
        TenantRegistry().authenticate("Bearer anything", "survival-1")
