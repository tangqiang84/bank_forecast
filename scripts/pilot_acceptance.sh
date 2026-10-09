#!/usr/bin/env bash
# 试点验收执行脚本（P2-5）：运行后端验收套件 PilotAcceptanceTest。
# 覆盖：导入成功率、模板识别率、自动匹配率、异常闭环、常用查询性能和验收指标可用性。
# 验收口径和实测结论见 docs/reports/ 下验收报告。
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

log() {
  printf '[pilot] %s\n' "$*"
}

log "运行试点验收套件（backend PilotAcceptanceTest）..."
(cd "$ROOT_DIR/backend" && ./mvnw -q test -Dtest='PilotAcceptanceTest')
log "验收套件通过"
log "提示：运行中服务可查询 GET /api/v1/metrics/pilot-acceptance 获取实时验收指标"
