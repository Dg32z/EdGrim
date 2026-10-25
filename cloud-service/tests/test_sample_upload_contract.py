"""The sample upload endpoint, asserted against the shared corpus.

One recording session, one label, one batch, one receipt. Storage is ticket #14; the contract phase owes
only the shapes and the count.
"""

from __future__ import annotations

from fastapi.testclient import TestClient

from app.main import create_app
from contract_assertions import assert_same_json
from contract_fixtures import request, response


def test_uploading_a_labelled_batch_answers_the_pinned_receipt() -> None:
    case = "sample_upload"
    client = TestClient(create_app())
    pinned_request = request(case)

    actual = client.post(
        pinned_request["path"],
        json=pinned_request["body"],
        headers=pinned_request["headers"],
    )

    assert actual.status_code == response(case)["status"]
    assert_same_json(response(case)["body"], actual.json())


def test_the_receipt_accounts_for_every_sample_sent() -> None:
    case = "sample_upload"
    client = TestClient(create_app())
    pinned_request = request(case)

    actual = client.post(
        pinned_request["path"],
        json=pinned_request["body"],
        headers=pinned_request["headers"],
    ).json()

    assert actual["accepted"] + actual["rejected"] == len(pinned_request["body"]["samples"])


def test_uploading_does_not_need_a_published_model() -> None:
    """A recording session is how the first model gets data, so a cold start must not block it."""
    from app.model import ModelRegistry

    case = "sample_upload"
    client = TestClient(create_app(registry=ModelRegistry.cold_start()))
    pinned_request = request(case)

    actual = client.post(
        pinned_request["path"],
        json=pinned_request["body"],
        headers=pinned_request["headers"],
    )

    assert actual.status_code == 200
