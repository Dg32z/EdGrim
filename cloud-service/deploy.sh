#!/usr/bin/env bash
#
# 部署云端服务到服务器。
#
# 这个脚本存在的唯一理由：**本地测试不过，就不许上传**。顺序写死在代码里，不靠记性。
#
#   1. 本地跑测试 —— 失败即中止，一个字节都不会发出去
#   2. 计算本地代码指纹
#   3. 上传代码（不含 .venv / config.yaml / 数据库 / 测试）
#   4. 重算服务器指纹，与本地逐个比对 —— 不一致即报错
#
# 用法：
#
#   ./deploy.sh user@host /opt/watchneko-cloud-analysis          # 部署
#   ./deploy.sh user@host /opt/watchneko-cloud-analysis --check  # 只核对，不上传
#   SSH_PORT=46011 ./deploy.sh root@1.2.3.4 /opt/wn --check
#
# 认证走你的 ssh 配置（密钥或交互式密码）。密码不会出现在这个文件里。
#
# 需要额外 ssh 选项时用 SSH_EXTRA_OPTS，例如：
#   SSH_EXTRA_OPTS="-o UserKnownHostsFile=/tmp/kh -o StrictHostKeyChecking=accept-new" \
#       ./deploy.sh root@1.2.3.4 /opt/wn
# 主机密钥的信任策略刻意不写死在这个脚本里——那是你的机器该决定的事。

set -euo pipefail

TARGET="${1:?用法: $0 <user@host> <远程目录> [--check]}"
REMOTE_DIR="${2:?用法: $0 <user@host> <远程目录> [--check]}"
MODE="${3:-deploy}"
SSH_PORT="${SSH_PORT:-22}"

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE"

# 空数组在 bash 3.2 + set -u 下展开会报错，所以用 ${arr[@]+...} 形式。
SSH_EXTRA=()
if [ -n "${SSH_EXTRA_OPTS:-}" ]; then
    read -r -a SSH_EXTRA <<< "$SSH_EXTRA_OPTS"
fi
SSH=(ssh -p "$SSH_PORT" -o ConnectTimeout=15 ${SSH_EXTRA[@]+"${SSH_EXTRA[@]}"} "$TARGET")

# 部署的内容：代码与依赖声明。刻意排除的东西见下面的排除列表。
INCLUDE=(app run.py requirements.txt conftest.py start.sh Dockerfile .dockerignore)

red()   { printf '\033[31m%s\033[0m\n' "$*"; }
green() { printf '\033[32m%s\033[0m\n' "$*"; }
dim()   { printf '\033[2m%s\033[0m\n' "$*"; }

fingerprint_local() {
    find "${INCLUDE[@]}" -type f -not -path '*__pycache__*' 2>/dev/null \
        | sort | xargs shasum -a 256 | awk '{print $1, $2}'
}

fingerprint_remote() {
    "${SSH[@]}" "cd '$REMOTE_DIR' && find ${INCLUDE[*]} -type f -not -path '*__pycache__*' 2>/dev/null | sort | xargs sha256sum | awk '{print \$1, \$2}'"
}

# ---------------------------------------------------------------- 1. 本地测试

echo "== 1/4 本地测试 =="
if ! .venv/bin/python -m pytest tests -q 2>&1 | tail -3; then
    red "测试失败 —— 中止部署，未上传任何文件。"
    exit 1
fi
green "测试通过"

# ---------------------------------------------------------------- 2. 本地指纹

echo
echo "== 2/4 本地代码指纹 =="
fingerprint_local > /tmp/wn-deploy-local.txt
LOCAL_COUNT=$(wc -l < /tmp/wn-deploy-local.txt | tr -d ' ')
LOCAL_SUM=$(sort /tmp/wn-deploy-local.txt | shasum -a 256 | cut -c1-16)
dim "  $LOCAL_COUNT 个文件，汇总 $LOCAL_SUM"

if [ "$MODE" = "--check" ]; then
    echo
    echo "== 只核对模式，不上传 =="
    fingerprint_remote > /tmp/wn-deploy-remote.txt
    REMOTE_SUM=$(sort /tmp/wn-deploy-remote.txt | shasum -a 256 | cut -c1-16)
    dim "  服务器 $REMOTE_SUM"
    if diff -q <(sort -k2 /tmp/wn-deploy-local.txt) <(sort -k2 /tmp/wn-deploy-remote.txt) >/dev/null; then
        green "一致：服务器上跑的就是刚通过测试的这份代码"
    else
        red "不一致："
        diff <(sort -k2 /tmp/wn-deploy-local.txt) <(sort -k2 /tmp/wn-deploy-remote.txt) | head -30
        exit 1
    fi
    rm -f /tmp/wn-deploy-local.txt /tmp/wn-deploy-remote.txt
    exit 0
fi

# ---------------------------------------------------------------- 3. 上传

echo
echo "== 3/4 上传 =="
"${SSH[@]}" "mkdir -p '$REMOTE_DIR'"
# 只打包 INCLUDE 里的东西：.venv、config.yaml、数据库、tests 都不进包，
# 前者是环境、中两者是运行期数据、后者生产机上不该有。
# BSD tar 会把选项后面的 --exclude 当成文件操作数，所以排除项必须写在文件列表之前。
tar czf - --exclude='__pycache__' --exclude='*.pyc' "${INCLUDE[@]}" \
    | "${SSH[@]}" "tar xzf - -C '$REMOTE_DIR'"
green "已上传"

# ---------------------------------------------------------------- 4. 校验

echo
echo "== 4/4 服务器指纹校验 =="
fingerprint_remote > /tmp/wn-deploy-remote.txt
REMOTE_COUNT=$(wc -l < /tmp/wn-deploy-remote.txt | tr -d ' ')
REMOTE_SUM=$(sort /tmp/wn-deploy-remote.txt | shasum -a 256 | cut -c1-16)
dim "  $REMOTE_COUNT 个文件，汇总 $REMOTE_SUM"

if [ "$LOCAL_COUNT" != "$REMOTE_COUNT" ] || ! diff -q <(sort -k2 /tmp/wn-deploy-local.txt) <(sort -k2 /tmp/wn-deploy-remote.txt) >/dev/null; then
    red "校验失败：服务器上的文件与本地不一致"
    diff <(sort -k2 /tmp/wn-deploy-local.txt) <(sort -k2 /tmp/wn-deploy-remote.txt) | head -30
    rm -f /tmp/wn-deploy-local.txt /tmp/wn-deploy-remote.txt
    exit 1
fi

rm -f /tmp/wn-deploy-local.txt /tmp/wn-deploy-remote.txt
green "校验通过：$LOCAL_COUNT 个文件逐一匹配"

echo
dim "代码已就位。依赖若有变动需在服务器上装："
dim "  $REMOTE_DIR/.venv/bin/pip install -r $REMOTE_DIR/requirements.txt"
dim "然后在 MCSManager 里重启实例。"
