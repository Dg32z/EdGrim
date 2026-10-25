"""Strict structural comparison against a fixture.

Strict on purpose: object key sets must match exactly (no extra key, no missing key) and numbers compare
numerically so ``0`` and ``0.0`` are the same wire value. This is what makes a renamed field a test failure
instead of a silent pass.
"""

from __future__ import annotations

from typing import Any


def assert_same_json(expected: Any, actual: Any, path: str = "$") -> None:
    if isinstance(expected, dict):
        if not isinstance(actual, dict):
            raise AssertionError(f"{path}: expected an object but was {actual!r}")
        if set(expected) != set(actual):
            raise AssertionError(
                f"{path}: field names differ: "
                f"missing={sorted(set(expected) - set(actual))} "
                f"unexpected={sorted(set(actual) - set(expected))}"
            )
        for key in expected:
            assert_same_json(expected[key], actual[key], f"{path}.{key}")
        return

    if isinstance(expected, list):
        if not isinstance(actual, list):
            raise AssertionError(f"{path}: expected an array but was {actual!r}")
        if len(expected) != len(actual):
            raise AssertionError(
                f"{path}: array length differs: expected {len(expected)} but was {len(actual)}"
            )
        for index, (expected_item, actual_item) in enumerate(zip(expected, actual)):
            assert_same_json(expected_item, actual_item, f"{path}[{index}]")
        return

    if isinstance(expected, bool) or isinstance(actual, bool):
        if not (isinstance(expected, bool) and isinstance(actual, bool)) or expected != actual:
            raise AssertionError(f"{path}: expected {expected!r} but was {actual!r}")
        return

    if isinstance(expected, (int, float)) and isinstance(actual, (int, float)):
        if expected != actual:
            raise AssertionError(f"{path}: expected {expected!r} but was {actual!r}")
        return

    if expected != actual:
        raise AssertionError(f"{path}: expected {expected!r} but was {actual!r}")
