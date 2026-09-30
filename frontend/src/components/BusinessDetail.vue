<script setup lang="ts">
import {
  RISK_LEVEL_LABELS,
  accountStatusLabel,
  allocationModeLabel,
  auditActionLabel,
  confidenceLevelLabel,
  connectionTypeLabel,
  contractStatusLabel,
  directionLabel,
  exceptionStatusLabel,
  exceptionTypeLabel,
  fieldNameLabel,
  genericStatusLabel,
  healthLevelLabel,
  idleLevelLabel,
  importJobStatusLabel,
  industryTemplateLabel,
  jobTypeLabel,
  matchStatusLabel,
  matchTypeLabel,
  modelNameLabel,
  projectStatusLabel,
  receivableStatusLabel,
  recordTypeLabel,
  reportTypeLabel,
  riskLevelLabel,
  severityLabel,
  taskStatusLabel,
  userStatusLabel,
} from '../utils/labels'

defineProps<{ data: Record<string, unknown> }>()

function entries(value: unknown) {
  return value && typeof value === 'object' && !Array.isArray(value)
    ? Object.entries(value as Record<string, unknown>)
    : []
}

function label(key: string) {
  return fieldNameLabel(key)
}

function riskLabel(value: string) {
  return Object.prototype.hasOwnProperty.call(RISK_LEVEL_LABELS, value)
    ? riskLevelLabel(value)
    : healthLevelLabel(value)
}

const KEY_VALUE_LABELS: Record<string, (value: string) => string> = {
  match_status: matchStatusLabel,
  direction: directionLabel,
  severity: severityLabel,
  exception_type: exceptionTypeLabel,
  risk_level: riskLabel,
  confidence_level: confidenceLevelLabel,
  record_type: recordTypeLabel,
  report_type: reportTypeLabel,
  job_type: jobTypeLabel,
  connection_type: connectionTypeLabel,
  idle_level: idleLevelLabel,
  allocation_mode: allocationModeLabel,
  match_type: matchTypeLabel,
  project_status: projectStatusLabel,
  health_level: healthLevelLabel,
  model_name: modelNameLabel,
  last_test_status: taskStatusLabel,
  user_status: userStatusLabel,
  industry_template: industryTemplateLabel,
  action: auditActionLabel,
}

function statusValue(value: unknown, context: Record<string, unknown>) {
  const text = String(value)
  if ('idle_level' in context || 'account_no_last4' in context) return accountStatusLabel(text)
  if ('exception_no' in context || 'exception_type' in context) return exceptionStatusLabel(text)
  if ('plan_amount' in context && 'paid_amount' in context) return receivableStatusLabel(text)
  if ('total_rows' in context || 'job_type' in context || 'preview_confirmed_at' in context)
    return importJobStatusLabel(text)
  if ('contract_no' in context) return contractStatusLabel(text)
  if ('login_name' in context) return userStatusLabel(text)
  if ('model_name' in context || 'horizon' in context) return taskStatusLabel(text)
  return genericStatusLabel(text)
}

function display(value: unknown) {
  if (value === null || value === undefined || value === '') return '-'
  if (typeof value === 'object') return Array.isArray(value) ? `${value.length} 条记录` : '已关联'
  return String(value)
}

function displayValue(key: string, value: unknown, context: Record<string, unknown>) {
  if (value === null || value === undefined || value === '') return '-'
  if (typeof value === 'object') return Array.isArray(value) ? `${value.length} 条记录` : '已关联'
  if (key === 'status') return statusValue(value, context)
  const mapper = KEY_VALUE_LABELS[key]
  if (mapper) return mapper(String(value))
  return String(value)
}

function listItemStatus(item: Record<string, unknown>) {
  const status = item.status ?? item.match_status
  if (status === null || status === undefined || status === '') return null
  const text = String(status)
  if ('match_status' in item) return matchStatusLabel(text)
  if ('plan_amount' in item) return receivableStatusLabel(text)
  if ('exception_no' in item || 'exception_type' in item) return exceptionStatusLabel(text)
  if ('contract_no' in item) return contractStatusLabel(text)
  return genericStatusLabel(text)
}

function listItemTail(item: Record<string, unknown>) {
  const mapped = listItemStatus(item)
  if (mapped !== null) return mapped
  return display(item.amount ?? item.description)
}

function timelineAction(item: Record<string, unknown>) {
  const action = item.action ?? item.event
  if (action === null || action === undefined || action === '') return '操作记录'
  return auditActionLabel(String(action))
}

function recordOf(value: unknown): Record<string, unknown> {
  return value as Record<string, unknown>
}

function list(value: unknown) {
  return Array.isArray(value) ? (value as Array<Record<string, unknown>>) : []
}
</script>

<template>
  <div class="business-detail">
    <template
      v-for="section in [
        'transaction',
        'project',
        'contract',
        'summary',
        'reconciliation',
        'forecast_job',
        'evaluation',
      ]"
      :key="section"
    >
      <section
        v-if="data[section] && typeof data[section] === 'object' && !Array.isArray(data[section])"
        class="detail-section"
      >
        <h3>
          {{
            section === 'transaction'
              ? '流水信息'
              : section === 'project'
                ? '项目信息'
                : section === 'contract'
                  ? '合同信息'
                  : section === 'reconciliation'
                    ? '对账差异'
                    : section === 'forecast_job'
                      ? '预测任务'
                      : section === 'evaluation'
                        ? '评估指标'
                        : '资金汇总'
          }}
        </h3>
        <dl class="detail-grid">
          <template v-for="[key, value] in entries(data[section])" :key="key"
            ><dt>{{ label(key) }}</dt>
            <dd>{{ displayValue(key, value, recordOf(data[section])) }}</dd></template
          >
        </dl>
      </section>
    </template>
    <section
      v-if="
        !data.transaction &&
        !data.project &&
        !data.contract &&
        !data.summary &&
        !data.reconciliation &&
        !data.forecast_job &&
        !data.evaluation
      "
      class="detail-section"
    >
      <h3>基本信息</h3>
      <dl class="detail-grid">
        <template v-for="[key, value] in entries(data)" :key="key"
          ><dt>{{ label(key) }}</dt>
          <dd>{{ displayValue(key, value, data) }}</dd></template
        >
      </dl>
    </section>
    <template
      v-for="key in [
        'contracts',
        'receivables',
        'transactions',
        'payments',
        'timeline',
        'exceptions',
        'allocations',
        'attachments',
      ]"
      :key="key"
      ><section v-if="list(data[key]).length" class="detail-section">
        <h3>
          {{
            key === 'contracts'
              ? '关联合同'
              : key === 'receivables'
                ? '应收计划'
                : key === 'transactions'
                  ? '关联流水'
                  : key === 'payments'
                    ? '付款事实'
                    : key === 'timeline'
                      ? '资金时间线'
                      : key === 'exceptions'
                        ? '关联异常'
                        : key === 'allocations'
                          ? '分配明细'
                          : '附件'
          }}
        </h3>
        <div class="mini-list">
          <div
            v-for="(item, index) in list(data[key])"
            :key="String(item.id ?? index)"
            class="mini-row"
          >
            <strong>{{
              display(
                item.title ??
                  item.contract_no ??
                  item.transaction_no ??
                  item.file_name ??
                  `记录 ${index + 1}`,
              )
            }}</strong
            ><span v-if="key === 'timeline'" class="meta">{{ display(item.date) }}</span
            ><span>{{ listItemTail(item) }}</span>
          </div>
        </div>
      </section></template
    >
    <section v-if="list(data.logs).length || list(data.audit_logs).length" class="detail-section">
      <h3>处理时间轴</h3>
      <ol class="timeline">
        <li
          v-for="(item, index) in [...list(data.logs), ...list(data.audit_logs)]"
          :key="String(item.id ?? index)"
        >
          <span class="timeline-dot" />
          <div>
            <strong>{{ timelineAction(item) }}</strong>
            <p class="meta">{{ display(item.created_at ?? item.detail ?? item.text) }}</p>
          </div>
        </li>
      </ol>
    </section>
  </div>
</template>
