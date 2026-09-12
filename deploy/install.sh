#!/usr/bin/env bash
#
# Alpha Trader VPS 一键部署（P4-7 / T407）。
#
# 前置：一台 Linux VPS（已装 git）。脚本会：
#   1. 安装 Docker + compose 插件（已装则跳过）
#   2. 把仓库克隆到 /opt/alpha-trader（已存在则复用、git pull）
#   3. 从 .env.example 生成 .env（已存在则不动你的密钥）
#   4. docker compose up -d（app + MySQL 8.4，restart: unless-stopped，重启自愈）
#
# 用法：
#   sudo bash deploy/install.sh [仓库地址]
#   仓库地址缺省为 git@github.com:ciaozn/alpha-trader.git（需配好 SSH key），
#   也可以传 https 地址：sudo bash deploy/install.sh https://github.com/ciaozn/alpha-trader.git

set -euo pipefail

REPO="${1:-git@github.com:ciaozn/alpha-trader.git}"
INSTALL_DIR="/opt/alpha-trader"

if [ "$(id -u)" -ne 0 ]; then
  echo "需要 root（安装 Docker 与写 /opt）：sudo bash $0" >&2
  exit 1
fi

echo "[1/4] Docker"
if command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
  echo "  已安装：$(docker --version)"
else
  echo "  安装中（官方脚本）..."
  curl -fsSL https://get.docker.com | sh
  systemctl enable --now docker
fi

echo "[2/4] 代码（${INSTALL_DIR}）"
if [ -d "${INSTALL_DIR}/.git" ]; then
  git -C "${INSTALL_DIR}" pull --ff-only
else
  git clone "${REPO}" "${INSTALL_DIR}"
fi
cd "${INSTALL_DIR}"

echo "[3/4] .env"
if [ -f .env ]; then
  echo "  已存在，保留你的配置不动（新增变量请对照 .env.example 手工补）"
else
  cp .env.example .env
  chmod 600 .env
  echo "  已从模板生成。**必填项**（用编辑器填，别贴进终端历史）："
  grep -E "^(BINANCE_|ALPHA_DB_PASSWORD|ALPHA_DB_ROOT_PASSWORD)" .env.example | sed 's/^/    /'
  echo
  read -r -p "  填完 .env 后按回车继续（Ctrl-C 可退出稍后再来）"
fi

echo "[4/4] 启动"
docker compose up -d
docker compose ps
echo
echo "完成。常用操作（都在 ${INSTALL_DIR} 下执行）："
echo "  docker compose logs -f app      # 跟踪应用日志"
echo "  docker compose restart app      # 重启应用"
echo "  docker compose down             # 停止（数据卷保留）"
echo "  curl -s localhost:8080/api/status | head   # 状态接口（compose 里仅绑 127.0.0.1）"
echo
echo "提醒：.env 里 ALPHA_TRADING_ENABLED 默认 false、ALPHA_PROFILE 默认 paper。"
echo "切 live 前先过一遍启动日志里的 Preflight 清单（SEC-02 的手工项也要逐条确认）。"
