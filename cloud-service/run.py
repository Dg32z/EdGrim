"""Entry point: build the service, start the retrain loop, serve.

The scheduler starts before uvicorn so a restart does not delay the first retrain by a full interval — a
service that has just come up is exactly when its data is most likely to have changed.
"""

from __future__ import annotations

import logging

import uvicorn

from app.config import load_settings
from app.serve import build_service

HOST = "0.0.0.0"
PORT = 8000


def main() -> None:
    logging.basicConfig(
        level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s"
    )
    settings = load_settings()
    service = build_service(settings)

    if not settings.is_authenticated:
        logging.getLogger(__name__).warning(
            "no tenants configured: every caller is treated as one local tenant. "
            "Set WATCHNEKO_TENANTS before letting anyone else reach this port."
        )

    service.ops.start()
    uvicorn.run(service.app, host=HOST, port=PORT)


if __name__ == "__main__":
    main()
