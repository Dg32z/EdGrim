"""Administrator sessions: a separate identity, and a cookie that cannot be forged."""

from __future__ import annotations

import time

import pytest

from app.admin_auth import AdminAuth


@pytest.fixture
def auth() -> AdminAuth:
    return AdminAuth(username="root", password="correct horse", secret="test-secret")


def test_valid_credentials_issue_a_verifiable_session(auth: AdminAuth) -> None:
    token = auth.login("root", "correct horse")

    assert token is not None
    assert auth.verify(token) == "root"


def test_a_wrong_password_issues_nothing(auth: AdminAuth) -> None:
    assert auth.login("root", "wrong") is None


def test_a_wrong_username_issues_nothing(auth: AdminAuth) -> None:
    assert auth.login("nobody", "correct horse") is None


def test_the_two_failures_are_indistinguishable(auth: AdminAuth) -> None:
    """The console must not become a username oracle."""
    assert auth.login("nobody", "correct horse") == auth.login("root", "wrong")


def test_a_tampered_signature_is_refused(auth: AdminAuth) -> None:
    token = auth.login("root", "correct horse")
    payload, _, _signature = token.rpartition(".")

    assert auth.verify(f"{payload}.deadbeef") is None


def test_a_tampered_payload_is_refused(auth: AdminAuth) -> None:
    token = auth.login("root", "correct horse")
    _payload, _, signature = token.rpartition(".")

    assert auth.verify(f"Zm9yZ2Vk|9999999999.{signature}") is None


def test_garbage_is_refused_rather_than_crashing(auth: AdminAuth) -> None:
    for token in ("", "no-dot", "a.b", None, "...."):
        assert auth.verify(token) is None


def test_an_expired_session_is_refused() -> None:
    auth = AdminAuth(username="root", password="pw", secret="s", session_ttl_seconds=-1)

    token = auth.login("root", "pw")

    assert auth.verify(token) is None


def test_a_session_signed_with_another_secret_is_refused() -> None:
    mine = AdminAuth(username="root", password="pw", secret="secret-a")
    theirs = AdminAuth(username="root", password="pw", secret="secret-b")

    assert theirs.verify(mine.login("root", "pw")) is None


def test_a_password_hash_can_be_supplied_instead_of_a_password() -> None:
    from app.admin_auth import _hash_password

    auth = AdminAuth(username="root", password_hash=_hash_password("pw"), secret="s")

    assert auth.verify(auth.login("root", "pw")) == "root"


def test_missing_credentials_are_refused_at_construction() -> None:
    with pytest.raises(ValueError):
        AdminAuth(username="root", password="", secret="s")
    with pytest.raises(ValueError):
        AdminAuth(username="", password="pw", secret="s")
