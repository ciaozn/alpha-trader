#!/usr/bin/env bash
#
# 在 VPS 上把 app 服务切到某个镜像标签（由发布流水线调用，也可以手动跑）。
#
#   bash deploy/update.sh ghcr.io/ciaozn/alpha-trader:v1.0.0 [/opt/alpha-trader]
#
# 设计要点：
#
#   * **只换 app，不动 MySQL**。业务库的数据在 mysql-data 卷里，重启数据库是没必要的风险。
#   * **等健康检查通过才算成功**。`docker compose up -d` 返回 0 只代表容器起来了，不代表应用
#     可用；启动失败（配置错、连不上库）时容器会一直重启，退出码却是 0。所以这里轮询健康状态。
#   * **失败自动回滚**到上一个镜像。回滚也是「换 app」，几秒钟的事。
#   * **单实例，会有秒级中断**。账本在内存里、且设计上只允许一个写者，所以不能并行跑新旧两份
#     来滚动更新。中断是安全的：重启后 StartupRecovery 会先同步交易所、再回放事件日志校验（T403）。
#   * **不用 sudo**：部署账号在 docker 组里即可。
#
# 环境变量：DRY_RUN=1 只打印将要执行的命令，不做任何改动。

set -euo pipefail

IMAGE_REF="${1:?用法: update.sh <镜像引用> [部署目录]}"
DEPLOY_DIR="${2:-/opt/alpha-trader}"
DRY_RUN="${DRY_RUN:-0}"
HEALTH_TIMEOUT_SECONDS="${HEALTH_TIMEOUT_SECONDS:-240}"

cd "$DEPLOY_DIR"

if [ ! -f .env ]; then
  echo "!! $DEPLOY_DIR/.env 不存在：先按 deploy/README.md 完成首次部署" >&2
  exit 2
fi

run() {
  if [ "$DRY_RUN" = "1" ]; then
    echo "  [dry-run] $*"
  else
    "$@"
  fi
}

# 探测类命令只读，dry-run 时也照常执行——否则「当前镜像」这一行会打印出假的命令文本。
current_container="$(docker compose ps -q app 2>/dev/null || true)"
previous_image=""
if [ -n "$current_container" ]; then
  previous_image="$(docker inspect --format '{{.Config.Image}}' "$current_container" 2>/dev/null || true)"
fi

echo "部署目录 : $DEPLOY_DIR"
echo "当前镜像 : ${previous_image:-（还没有运行中的 app）}"
echo "目标镜像 : $IMAGE_REF"

if [ "$DRY_RUN" != "1" ] && [ "$previous_image" = "$IMAGE_REF" ]; then
  # 幂等：流水线重跑、或者手动再点一次，不应该把服务重启一遍。
  echo "已经是这个镜像了，跳过。"
  exit 0
fi

echo
echo "[1/4] 拉取镜像"
ALPHA_IMAGE="$IMAGE_REF" run docker compose pull app

echo
echo "[2/4] 切换 app 容器（MySQL 保持不动）"
# --no-build：VPS 上只拉不构建。镜像不在本地时 compose 会报错，这正是我们要的：
# 宁可直接失败，也不要在服务器上悄悄编译出一个和标签不符的镜像。
ALPHA_IMAGE="$IMAGE_REF" run docker compose up -d --no-build app

echo
echo "[3/4] 等待健康检查（最长 ${HEALTH_TIMEOUT_SECONDS}s）"
healthy=0
if [ "$DRY_RUN" = "1" ]; then
  echo "  [dry-run] 轮询 docker inspect 的 .State.Health.Status 直到 healthy"
  healthy=1
else
  container="$(docker compose ps -q app)"
  elapsed=0
  while [ "$elapsed" -lt "$HEALTH_TIMEOUT_SECONDS" ]; do
    # 容器可能在启动阶段就崩掉，所以先看它是不是还活着。
    status="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$container" 2>/dev/null || echo missing)"
    case "$status" in
      healthy)
        healthy=1
        echo "  健康检查通过（用时 ${elapsed}s）"
        break
        ;;
      missing)
        echo "  容器已消失，启动失败。最近日志："
        docker compose logs --tail 30 app || true
        break
        ;;
    esac
    sleep 5
    elapsed=$((elapsed + 5))
  done
  if [ "$healthy" != "1" ] && [ "$status" != "missing" ]; then
    echo "  ${HEALTH_TIMEOUT_SECONDS}s 内未通过健康检查（最后状态=${status}）。最近日志："
    docker compose logs --tail 30 app || true
  fi
fi

if [ "$healthy" = "1" ]; then
  echo
  echo "[4/4] 清理一周前的旧镜像"
  run docker image prune -f --filter "until=168h" >/dev/null || true
  echo
  echo "部署完成：$IMAGE_REF"
  exit 0
fi

echo
echo "!! 部署失败"
if [ -z "$previous_image" ] || [ "$previous_image" = "$IMAGE_REF" ]; then
  echo "   没有可回滚的上一版（这是首次部署）。请先看日志定位原因。" >&2
  exit 1
fi

echo "   回滚到 $previous_image ..."
ALPHA_IMAGE="$previous_image" docker compose up -d --no-build app
echo "   已回滚。失败版本 $IMAGE_REF 的日志在上方。" >&2
exit 1
