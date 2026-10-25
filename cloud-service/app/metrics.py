"""Metrics, in the Prometheus text exposition format.

Written by hand rather than pulled from a client library: the surface is five counters and a gauge, the
format is a dozen lines of text, and a dependency that exists only to print numbers is a dependency that
has to be kept alive.

The five families here are not decoration. Each one is a number an operator needs in order to answer a
specific question, and together they are what the rollback decision reads:

* **samples per tenant** — is the quota sane, and is a server sending far more than it should?
* **verdict distribution** — has the model started calling everything cheat, or everything legit?
* **model version** — which model is answering right now?
* **error rate** — is the service healthy at all?
* **queue backlog** — is work piling up faster than it drains?
"""

from __future__ import annotations

from collections import Counter
from dataclasses import dataclass, field

LABELS = ("cheat_like", "legit_like", "uncertain")


@dataclass
class Metrics:
    """Counters the service keeps as it serves."""

    samples_by_tenant: Counter[str] = field(default_factory=Counter)
    verdicts: Counter[str] = field(default_factory=Counter)
    requests: int = 0
    errors: int = 0
    queue_backlog: int = 0
    model_id: str = "none"
    dataset_id: str = "none"
    rollbacks: int = 0

    def record_samples(self, tenant_id: str, count: int) -> None:
        self.samples_by_tenant[tenant_id] += count

    def record_verdicts(self, labels: list[str]) -> None:
        for label in labels:
            self.verdicts[label] += 1

    def record_request(self) -> None:
        self.requests += 1

    def record_error(self) -> None:
        self.errors += 1

    def record_publish(self, model_id: str, dataset_id: str) -> None:
        self.model_id = model_id
        self.dataset_id = dataset_id

    def record_rollback(self) -> None:
        self.rollbacks += 1

    @property
    def error_rate(self) -> float:
        return 0.0 if self.requests == 0 else self.errors / self.requests

    @property
    def total_verdicts(self) -> int:
        return sum(self.verdicts.values())

    def cheat_ratio(self) -> float:
        """Share of verdicts that came back cheat-like. The signal a runaway model shows up in first."""
        total = self.total_verdicts
        return 0.0 if total == 0 else self.verdicts.get("cheat_like", 0) / total

    def snapshot(self) -> dict:
        """The same numbers as :meth:`render`, as data — what the console's dashboard reads."""
        return {
            "samples_by_tenant": dict(sorted(self.samples_by_tenant.items())),
            "verdicts": {label: self.verdicts.get(label, 0) for label in LABELS},
            "requests": self.requests,
            "errors": self.errors,
            "error_rate": round(self.error_rate, 6),
            "queue_backlog": self.queue_backlog,
            "model_id": self.model_id,
            "dataset_id": self.dataset_id,
            "rollbacks": self.rollbacks,
            "cheat_ratio": round(self.cheat_ratio(), 6),
        }

    def render(self) -> str:
        """The whole surface as Prometheus text."""
        lines: list[str] = []

        lines += _family(
            "watchneko_samples_total",
            "counter",
            "Feature samples accepted from a tenant.",
            [
                ({"tenant": tenant}, value)
                for tenant, value in sorted(self.samples_by_tenant.items())
            ],
        )
        lines += _family(
            "watchneko_verdicts_total",
            "counter",
            "Verdicts issued, by conclusion.",
            [( {"verdict": label}, self.verdicts.get(label, 0)) for label in LABELS],
        )
        lines += _family(
            "watchneko_requests_total", "counter", "Inference requests served.", [({}, self.requests)]
        )
        lines += _family(
            "watchneko_errors_total", "counter", "Requests that ended in an error.", [({}, self.errors)]
        )
        lines += _family(
            "watchneko_error_rate",
            "gauge",
            "Share of requests that ended in an error.",
            [({}, round(self.error_rate, 6))],
        )
        lines += _family(
            "watchneko_queue_backlog",
            "gauge",
            "Units of work waiting to be drained.",
            [({}, self.queue_backlog)],
        )
        lines += _family(
            "watchneko_rollbacks_total", "counter", "Model rollbacks performed.", [({}, self.rollbacks)]
        )
        lines += _family(
            "watchneko_model_info",
            "gauge",
            "The model currently answering, and the dataset behind it.",
            [({"model_id": self.model_id, "dataset_id": self.dataset_id}, 1)],
        )

        return "\n".join(lines) + "\n"


def _family(
    name: str, kind: str, help_text: str, samples: list[tuple[dict[str, str], int | float]]
) -> list[str]:
    lines = [f"# HELP {name} {help_text}", f"# TYPE {name} {kind}"]
    for labels, value in samples:
        rendered = ",".join(f'{key}="{_escape(value)}"' for key, value in labels.items())
        selector = f"{{{rendered}}}" if rendered else ""
        lines.append(f"{name}{selector} {value}")
    return lines


def _escape(value: str) -> str:
    return str(value).replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n")
