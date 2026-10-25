"""Entry point: load (or generate) the config, start the retrain loop, serve.

The config is a file this program writes on first run and then never touches again, so a fresh checkout
starts working immediately and every later change is the operator's edit rather than ours.
"""

from __future__ import annotations

import argparse
import logging
from pathlib import Path

import uvicorn

from app.config import DEFAULT_FILENAME, load_or_create
from app.serve import build_service

DEFAULT_CONFIG_PATH = Path(__file__).parent / DEFAULT_FILENAME


def main() -> None:
    parser = argparse.ArgumentParser(description="WatchNeko cloud service")
    parser.add_argument(
        "--config",
        type=Path,
        default=DEFAULT_CONFIG_PATH,
        help=f"配置文件路径（默认 {DEFAULT_CONFIG_PATH}）",
    )
    arguments = parser.parse_args()

    logging.basicConfig(
        level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s"
    )
    log = logging.getLogger("watchneko")

    settings, created = load_or_create(arguments.config)
    service = build_service(settings)

    if created:
        # Printed, not just written: the first run has to be usable without the operator opening a file.
        log.warning("已生成配置文件 %s", arguments.config)
        log.warning("管理员账号 %s，密码 %s", settings.admin_username, settings.admin_password)
        log.warning("请修改密码后重启；该密码只在这里和配置文件里出现。")

    if not settings.has_admin_password:
        log.warning("未设置 admin.password，控制台不会挂载")
    if service.tenants.list() == []:
        log.warning("尚无租户：先登录控制台创建，或所有调用者会被当作同一个本地租户")

    if settings.auto_retrain:
        service.ops.start()
    else:
        log.info("training.interval_seconds 为 0，自动重训已关闭，仅控制台可手动触发")

    uvicorn.run(service.app, host=settings.host, port=settings.port)


if __name__ == "__main__":
    main()
