"""The model metadata endpoint, asserted against the shared corpus.

This is what the plugin fetches before it dares to send anything, and the 404 is the cold start: a fresh
deployment has no model, and that is an absence rather than a failure.
"""

from __future__ import annotations

from fastapi.testclient import TestClient

from app.main import create_app
from app.model import ModelRegistry
from contract_assertions import assert_same_json
from contract_fixtures import request, response


def test_a_published_model_answers_its_version_dataset_and_thresholds() -> None:
    case = "model_metadata"
    client = TestClient(create_app())
    pinned_request = request(case)

    actual = client.get(pinned_request["path"], headers=pinned_request["headers"])

    assert actual.status_code == response(case)["status"]
    assert_same_json(response(case)["body"], actual.json())


def test_a_deployment_with_no_model_answers_the_pinned_no_model_error() -> None:
    case = "model_metadata_absent"
    client = TestClient(create_app(registry=ModelRegistry.cold_start()))
    pinned_request = request(case)

    actual = client.get(pinned_request["path"], headers=pinned_request["headers"])

    assert actual.status_code == response(case)["status"]
    assert_same_json(response(case)["body"], actual.json())


def test_the_thresholds_published_are_the_ones_that_produced_the_verdicts() -> None:
    """A threshold is only meaningful for the model it was tuned with, so both come from one place."""
    client = TestClient(create_app())

    metadata = client.get("/v1/model", headers=request("model_metadata")["headers"]).json()
    inference = client.post(
        "/v1/inference",
        json=request("inference_single")["body"],
        headers=request("inference_single")["headers"],
    ).json()

    assert inference["model_id"] == metadata["model_id"]
