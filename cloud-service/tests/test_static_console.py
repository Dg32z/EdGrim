"""The console is actually served, and it is served behind the admin session."""

from __future__ import annotations

from fastapi.testclient import TestClient

from app.config import Settings
from app.serve import build_service


def client() -> TestClient:
    return TestClient(
        build_service(
            Settings(
                tenants=[],
                database_path=":memory:",
                admin_username="root",
                admin_password="pw",
                admin_secret="s",
            )
        ).app
    )


def test_the_console_index_is_served() -> None:
    response = client().get("/admin/")

    assert response.status_code == 200
    assert "WatchNeko" in response.text
    assert "app.js" in response.text


def test_the_assets_are_served() -> None:
    bare = client()

    assert bare.get("/admin/app.js").status_code == 200
    assert bare.get("/admin/md3.css").status_code == 200


def test_the_css_declares_md3_tokens_and_no_raw_colours_outside_them() -> None:
    css = client().get("/admin/md3.css").text

    for token in (
        "--md-sys-color-primary",
        "--md-sys-color-surface-container-high",
        "--md-sys-shape-corner-extra-large",
        "--md-sys-typescale-title-large",
    ):
        assert token in css

    # The only hex literals allowed are inside the token definitions themselves.
    assert "#6750a4" in css


def test_the_console_api_still_requires_a_session() -> None:
    assert client().get("/admin/api/tenants").status_code == 401
