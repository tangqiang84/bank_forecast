#!/usr/bin/env bash
# 一键关停 frontend、backend、analytics 三个本地开发服务。
# 先发送 SIGTERM 等待优雅退出，超时后升级为 SIGKILL，并清理子进程与 PID 文件。
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PID_DIR="$ROOT_DIR/scripts/.pids"

BACKEND_PORT="${SERVER_PORT:-8080}"
ANALYTICS_PORT="${ANALYTICS_PORT:-8001}"
FRONTEND_PORT="${FRONTEND_PORT:-5173}"

log() {
  printf '[stop] %s\n' "$*"
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

# 按 PID 优雅停止单个服务：TERM 子进程与主进程 → 等待 → 超时后 KILL。
stop_by_pid() {
  local name="$1" pid="$2" timeout="$3"
  if ! is_running "$pid"; then
    return 1
  fi
  log "停止 $name（PID $pid，优雅等待 ${timeout}s）..."
  local children
  children="$(pgrep -P "$pid" 2>/dev/null || true)"
  # 先 TERM 子进程（如 pnpm 拉起的 vite），再 TERM 主进程
  for child in $children; do
    kill -TERM "$child" 2>/dev/null || true
  done
  kill -TERM "$pid" 2>/dev/null || true

  local waited=0
  while [ "$waited" -lt "$timeout" ]; do
    if ! is_running "$pid"; then
      log "$name 已退出"
      return 0
    fi
    sleep 1
    waited=$((waited + 1))
  done

  log "$name 未在 ${timeout}s 内退出，发送 SIGKILL"
  for child in $(pgrep -P "$pid" 2>/dev/null || true); do
    kill -KILL "$child" 2>/dev/null || true
  done
  kill -KILL "$pid" 2>/dev/null || true
  sleep 1
  if is_running "$pid"; then
    log "$name 停止失败（PID $pid 仍存在），请手动处理"
    return 2
  fi
  return 0
}

# PID 文件缺失时按命令行特征兜底匹配，避免孤儿进程残留。
fallback_pids() {
  local pattern="$1"
  pgrep -f "$pattern" 2>/dev/null || true
}

stop_service() {
  local name="$1" timeout="$2" pattern="$3"
  local pid
  pid="$(read_pid "$name")"
  if ! is_running "$pid"; then
    local found
    found="$(fallback_pids "$pattern")"
    if [ -n "$found" ]; then
      pid="$(printf '%s\n' "$found" | head -n 1)"
      log "$name 无有效 PID 记录，按特征匹配到 PID $pid"
    fi
  fi
  stop_by_pid "$name" "$pid" "$timeout"
  rm -f "$(pid_file "$name")"
}

# 逆序停止：先停入口层，再停后端，最后停计算服务。
stop_service frontend 10 "node .*/vite"
stop_service backend 30 "BankForecastApplication"
stop_service analytics 15 "bank_forecast_analytics"

# 校验端口已释放
failed=0
for port in "$FRONTEND_PORT" "$BACKEND_PORT" "$ANALYTICS_PORT"; do
  if lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
    log "端口 $port 仍被占用：$(lsof -nP -iTCP:"$port" -sTCP:LISTEN | tail -n +2)"
    failed=1
  fi
done

if [ "$failed" -eq 0 ]; then
  log "全部服务已停止，端口已释放"
else
  log "存在未释放端口，请检查上述进程"
  exit 1
fi
