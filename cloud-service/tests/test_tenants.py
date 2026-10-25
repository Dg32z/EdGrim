"""Tenants: keys hashed, rotation real, revocation effective immediately."""

from __future__ import annotations

import pytest

from app.tenants import KEY_PREFIX_LENGTH, TenantStore


@pytest.fixture
def store() -> TenantStore:
    return TenantStore()


def test_creating_a_tenant_returns_its_key_exactly_once(store: TenantStore) -> None:
    record, key = store.create("t1", servers=["s1"])

    assert record.tenant_id == "t1"
    assert key.startswith("wnk_")
    assert record.api_key_prefix == key[:KEY_PREFIX_LENGTH]
    assert key not in str(store.list()), "the plaintext key must never be readable again"


def test_the_key_authenticates_the_tenant_it_was_created_for(store: TenantStore) -> None:
    _, key = store.create("t1", servers=["s1"])

    assert store.authenticate(key, "s1") == "t1"


def test_a_server_outside_the_tenant_is_refused(store: TenantStore) -> None:
    _, key = store.create("t1", servers=["s1"])

    assert store.authenticate(key, "s2") is None


def test_a_tenant_with_no_declared_servers_accepts_any(store: TenantStore) -> None:
    _, key = store.create("t1", servers=[])

    assert store.authenticate(key, "anything") == "t1"


def test_an_unknown_key_is_refused(store: TenantStore) -> None:
    store.create("t1")
    assert store.authenticate("wnk_nope", "s1") is None


def test_duplicate_tenant_ids_are_refused(store: TenantStore) -> None:
    store.create("t1")
    with pytest.raises(ValueError, match="already exists"):
        store.create("t1")


def test_a_blank_tenant_id_is_refused(store: TenantStore) -> None:
    with pytest.raises(ValueError, match="blank"):
        store.create("   ")


def test_rotating_replaces_the_key_and_kills_the_old_one(store: TenantStore) -> None:
    _, old_key = store.create("t1", servers=["s1"])

    _, new_key = store.rotate("t1")

    assert new_key != old_key
    assert store.authenticate(old_key, "s1") is None, "the old key must stop working immediately"
    assert store.authenticate(new_key, "s1") == "t1"


def test_rotating_an_unknown_tenant_is_refused(store: TenantStore) -> None:
    with pytest.raises(KeyError):
        store.rotate("nope")


def test_revoking_cuts_off_authentication_without_deleting_data(store: TenantStore) -> None:
    _, key = store.create("t1")

    record = store.revoke("t1")

    assert record.is_revoked
    assert store.authenticate(key, "s1") is None
    assert store.get("t1") is not None, "revocation is about access, not erasure"


def test_a_revoked_tenants_key_cannot_be_rotated_back_to_life(store: TenantStore) -> None:
    store.create("t1")
    store.revoke("t1")

    with pytest.raises(ValueError, match="revoked"):
        store.rotate("t1")


def test_updating_servers_and_quota_takes_effect(store: TenantStore) -> None:
    _, key = store.create("t1", servers=["s1"], quota_per_minute=100)

    record = store.update("t1", servers=["s1", "s2"], quota_per_minute=250)

    assert record.servers == ("s1", "s2")
    assert record.quota_per_minute == 250
    assert store.authenticate(key, "s2") == "t1"


def test_authentication_records_last_used(store: TenantStore) -> None:
    _, key = store.create("t1")
    assert store.get("t1").last_used_at is None

    store.authenticate(key, "s1")

    assert store.get("t1").last_used_at is not None


def test_deleting_removes_the_tenant(store: TenantStore) -> None:
    store.create("t1")
    store.delete("t1")
    assert store.get("t1") is None


def test_two_tenants_cannot_share_a_key(store: TenantStore) -> None:
    _, key = store.create("t1")
    record, other_key = store.create("t2")

    assert key != other_key
    assert store.authenticate(key, "s") == "t1"
    assert store.authenticate(other_key, "s") == "t2"
