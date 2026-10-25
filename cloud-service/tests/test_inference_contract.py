"""The inference endpoint, asserted against the shared corpus.

The service is handed the exact request body the corpus pins and must answer with the exact response body
the corpus pins. The Java tests read the same two files, which is why a field rename on either side turns
both suites red.
"""

from __future__ import annotations

from fastapi.testclient import TestClient

from app.main import create_app
from contract_assertions import assert_same_json
from contract_fixtures import request, response


def post_fixture_request(client: TestClient, case_name: str):
    pinned_request = request(case_name)
    return client.post(
        pinned_request["path"],
        json=pinned_request["body"],
        headers=pinned_request["headers"],
    )


def test_single_sample_inference_answers_the_pinned_verdict() -> None:
    case = "inference_single"
    client = TestClient(create_app())

    actual = post_fixture_request(client, case)

    assert actual.status_code == response(case)["status"]
    assert_same_json(response(case)["body"], actual.json())


def test_batch_inference_answers_one_verdict_per_sample_in_request_order() -> None:
    case = "inference_batch"
    client = TestClient(create_app())

    actual = post_fixture_request(client, case)

    assert actual.status_code == response(case)["status"]
    assert_same_json(response(case)["body"], actual.json())

    requested_ids = [sample["sample_id"] for sample in request(case)["body"]["samples"]]
    answered_ids = [verdict["sample_id"] for verdict in actual.json()["verdicts"]]
    assert answered_ids == requested_ids
