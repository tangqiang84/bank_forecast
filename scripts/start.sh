#!/usr/bin/env bash
# 一键启动 analytics、backend、frontend 三个本地开发服务。
# 日志写入 scripts/logs/，PID 写入 scripts/.pids/，停止请使用 scripts/stop.sh。
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_DIR="$ROOT_DIR/scripts/logs"
PID_DIR="$ROOT_DIR/scripts/.pids"
mkdir -p "$LOG_DIR" "$PID_DIR"

BACKEND_PORT="${SERVER_PORT:-8080}"
ANALYTICS_PORT="${ANALYTICS_PORT:-8001}"
FRONTEND_PORT="${FRONTEND_PORT:-5173}"
export VITE_BACKEND_BASE_URL="${VITE_BACKEND_BASE_URL:-http://localhost:${BACKEND_PORT}}"

BACKEND_HEALTH="http://localhost:${BACKEND_PORT}/api/v1/health"
ANALYTICS_HEALTH="http://localhost:${ANALYTICS_PORT}/health"
FRONTEND_HEALTH="http://localhost:${FRONTEND_PORT}/"

log() {
  printf '[start] %s\n' "$*"
}

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    log "缺少命令：$1，请先安装后重试"
    exit 1
  fi
}

load_env_file() {
  local file="$1"
  if [ -f "$file" ]; then
    set -a
    # shellcheck disable=SC1090
    . "$file"
    set +a
  fi
}

pid_file() {
  printf '%s/%s.pid' "$PID_DIR" "$1"
}

read_pid() {
  cat "$(pid_file "$1")" 2>/dev/null || true
}

is_running() {
  local pid="$1"
  [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null
}

# 等待健康检查通过；若进程提前退出则立即失败。
wait_healthy() {
  local name="$1" url="$2" pid="$3" timeout="$4" waited=0
  while [ "$waited" -lt "$timeout" ]; do
    if ! is_running "$pid"; then
      log "$name 进程已退出，启动失败，详见 $LOG_DIR/$name.log"
      return 1
    fi
    if curl -fsS -o /dev/null --max-time 2 "$url" 2>/dev/null; then
      log "$name 健康检查通过：$url"
      return 0
    fi
    sleep 2
    waited=$((waited + 2))
  done
  log "$name 健康检查超时（${timeout}s）：$url，详见 $LOG_DIR/$name.log"
  return 1
}

STARTED=()

start_service() {
  local name="$1" workdir="$2" health_url="$3" timeout="$4"
  shift 4
  local pid
  pid="$(read_pid "$name")"
  if is_running "$pid"; then
    log "$name 已在运行（PID $pid），跳过；如需重启请先执行 scripts/stop.sh"
    return 0
  fi
  rm -f "$(pid_file "$name")"
  log "启动 $name（日志：$LOG_DIR/$name.log）..."
  (
    cd "$workdir"
    load_env_file "$workdir/.env"
    nohup "$@" >>"$LOG_DIR/$name.log" 2>&1 &
    echo $! >"$(pid_file "$name")"
  )
  pid="$(read_pid "$name")"
  if wait_healthy "$name" "$health_url" "$pid" "$timeout"; then
    STARTED+=("$name")
    return 0
  fi
  # 回滚：停止本次已启动的服务
  log "启动失败，回滚本次已启动的服务..."
  "$ROOT_DIR/scripts/stop.sh" || true
  exit 1
}

require_cmd curl
require_cmd uv
require_cmd pnpm
require_cmd java

# backend 启动强制要求 JWT_SECRET；无 backend/.env 且未导出环境变量时，
# 自动生成本地开发专用随机值写入 backend/.env（已 gitignore，权限 600）。
ensure_backend_env() {
  local env_file="$ROOT_DIR/backend/.env"
  if [ -f "$env_file" ]; then
    return 0
  fi
  if [ -n "${JWT_SECRET:-}" ] && [ -n "${DEV_ADMIN_PASSWORD:-}" ]; then
    return 0
  fi
  require_cmd openssl
  cat >"$env_file" <<EOF
# 由 scripts/start.sh 自动生成的本地开发配置（已被 .gitignore 忽略，请勿提交）
SPRING_PROFILES_ACTIVE=dev
JWT_SECRET=${JWT_SECRET:-$(openssl rand -hex 32)}
DEV_ADMIN_PASSWORD=${DEV_ADMIN_PASSWORD:-$(openssl rand -hex 8)}
EOF
  chmod 600 "$env_file"
  log "未检测到 backend/.env，已自动生成本地开发配置：$env_file"
  log "演示账号密码为其中 DEV_ADMIN_PASSWORD（删除该文件后重启会重新生成）"
}

ensure_backend_env

start_service analytics "$ROOT_DIR/analytics" "$ANALYTICS_HEALTH" 60 \
  uv run python -m bank_forecast_analytics

start_service backend "$ROOT_DIR/backend" "$BACKEND_HEALTH" 180 \
  ./mvnw spring-boot:run -Dspring-boot.run.fork=false

start_service frontend "$ROOT_DIR/frontend" "$FRONTEND_HEALTH" 60 \
  pnpm dev

log "全部服务已启动："
log "  backend   http://localhost:${BACKEND_PORT}  (PID $(read_pid backend))"
log "  analytics http://localhost:${ANALYTICS_PORT} (PID $(read_pid analytics))"
log "  frontend  http://localhost:${FRONTEND_PORT}  (PID $(read_pid frontend))"
log "停止全部服务：bank_forecast/scripts/stop.sh"
