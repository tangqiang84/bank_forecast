<script setup lang="ts">
import type { ReportTask } from '../services/reports'
import { healthLevelLabel } from '../utils/labels'
import { formatCurrency } from '../utils/number'

defineProps<{ report: ReportTask }>()
</script>

<template>
  <template v-if="report.result">
    <div class="report-kpi-grid">
      <div>
        <span class="label">报表名称</span><strong>{{ report.result.report_name || '-' }}</strong>
      </div>
      <div>
        <span class="label">净现金流</span
        ><strong>{{ formatCurrency(String(report.result.net_cashflow ?? '0')) }}</strong>
      </div>
      <div v-if="report.report_type === 'health'">
        <span class="label">健康评分</span
        ><strong class="health-score">{{ report.result.health_score ?? '-' }}</strong>
      </div>
      <div v-if="report.report_type === 'health'">
        <span class="label">健康等级</span
        ><strong>{{ healthLevelLabel(String(report.result.health_level ?? '')) }}</strong>
      </div>
    </div>
    <div v-if="report.report_type === 'health'" class="risk-list">
      <h3>风险项</h3>
      <div
        v-for="risk in report.result.risk_items as Array<Record<string, unknown>>"
        v-if="Array.isArray(report.result.risk_items) && report.result.risk_items.length"
        :key="String(risk.title)"
        class="list-row"
      >
        <span>{{ risk.title }}：{{ risk.description }}</span
        ><span class="pill">{{ risk.count }}</span>
      </div>
      <p v-else class="empty-state">当前未发现风险项。</p>
    </div>
    <div v-else class="report-summary-grid">
      <span>收入 {{ formatCurrency(String(report.result.income_total ?? '0')) }}</span
      ><span>支出 {{ formatCurrency(String(report.result.expense_total ?? '0')) }}</span
      ><span>交易 {{ report.result.transaction_count ?? 0 }} 笔</span
      ><span>匹配率 {{ (Number(report.result.match_rate ?? 0) * 100).toFixed(1) }}%</span>
    </div>
  </template>
  <p v-else class="empty-state">该报表暂无结果内容。</p>
</template>
