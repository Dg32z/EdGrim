"""Reader for the shared golden fixture corpus at ``contract/fixtures``.

Both sides of the wire read this same directory: the Python tests here and the Java tests in
``common/src/test/java/cc/watchneko/cloud``. That shared read is the whole anti-drift mechanism, so this
module deliberately does no interpretation — it hands back the parsed document and lets each test assert
against it.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

CORPUS = Path(__file__).resolve().parents[2] / "contract" / "fixtures"


def directory() -> Path:
    if not CORPUS.is_dir():
        raise AssertionError(f"shared fixture corpus not found at {CORPUS}")
    return CORPUS


def case_names() -> list[str]:
    """Every case in the corpus, in a stable order."""
    return sorted(path.stem for path in directory().glob("*.json"))


def document(case_name: str) -> dict[str, Any]:
    return json.loads((directory() / f"{case_name}.json").read_text(encoding="utf-8"))


def request(case_name: str) -> dict[str, Any]:
    return document(case_name)["request"]


def response(case_name: str) -> dict[str, Any]:
    return document(case_name)["response"]
