#!/usr/bin/env bash
#
# 启动云端服务。MCSManager 的实例启动命令就填：bash /opt/watchneko-cloud-analysis/start.sh
#
# 路径由脚本自身位置推导，不硬编码：MCSManager 会把进程的工作目录设成实例目录，
# 相对路径在那里会指错地方，而硬编码的绝对路径在本地开发时又用不了。
#
# exec 让 python 取代这个 shell，SIGTERM 才能直接到达 uvicorn 完成优雅关闭——
# 中间隔一层 shell 的话，MCSManager 点「停止」会变成超时后强杀。

set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

exec "$DIR/.venv/bin/python" "$DIR/run.py" --config "$DIR/config.yaml"
