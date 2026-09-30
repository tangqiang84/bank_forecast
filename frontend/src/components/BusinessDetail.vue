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
import { formatCurrency } from '../utils/number'

defineProps<{ data: Record<string, unknown> }>()

const NAMED_SECTIONS = [
  'transaction',
  'project',
  'contract',
  'summary',
  'reconciliation',
  'forecast_job',
  'evaluation',
  'result',
  'exception',
]
const SECTION_TITLES: Record<string, string> = {
  transaction: '流水信息',
  project: '项目信息',
  contract: '合同信息',
  summary: '资金汇总',
  reconciliation: '对账差异',
  forecast_job: '预测任务',
  evaluation: '评估指标',
  result: '结果指标',
  exception: '异常信息',
}
const LIST_KEYS = [
  'contracts',
  'receivables',
  'transactions',
  'payments',
  'timeline',
  'exceptions',
  'allocations',
  'attachments',
  'matches',
  'source',
]
const LIST_TITLES: Record<string, string> = {
  contracts: '关联合同',
  receivables: '应收计划',
  transactions: '关联流水',
  payments: '付款事实',
  timeline: '资金时间线',
  exceptions: '关联异常',
  allocations: '分配明细',
  attachments: '附件',
  matches: '匹配结果',
  source: '来源对象',
}
const TIMELINE_KEYS = ['logs', 'audit_logs']
const CONSUMED_KEYS = new Set([...NAMED_SECTIONS, ...LIST_KEYS, ...TIMELINE_KEYS])

// 技术字段隐藏：主键、租户、逻辑删除和外键；job_id（任务编号）与 owner_user_id（处理人）保留。
function isHiddenKey(key: string) {
  if (key === 'id' || key === 'tenant_id' || key === 'deleted_at') return true
  return key.endsWith('_id') && key !== 'job_id' && key !== 'owner_user_id'
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value)
}

function entries(value: unknown) {
  return isRecord(value) ? Object.entries(value) : []
}

function recordOf(value: unknown): Record<string, unknown> {
  return value as Record<string, unknown>
}

function sectionScalars(value: unknown) {
  return entries(value).filter(
    ([key, entryValue]) => !isHiddenKey(key) && !Array.isArray(entryValue),
  )
}

function sectionArrays(value: unknown) {
  return entries(value).filter(
    ([key, entryValue]) => !isHiddenKey(key) && Array.isArray(entryValue) && entryValue.length > 0,
  )
}

function leftoverEntries(data: Record<string, unknown>) {
  return entries(data).filter(
    ([key, value]) =>
      !CONSUMED_KEYS.has(key) &&
      !isHiddenKey(key) &&
      (value === null || value === undefined || typeof value !== 'object'),
  )
}

function recordList(value: unknown) {
  return Array.isArray(value) ? (value.filter(isRecord) as Array<Record<string, unknown>>) : []
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
  transaction_match_status: matchStatusLabel,
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

// 命名区块内嵌数组（如报表 result 的 daily_breakdown/risk_items）的通用行渲染
const ROW_TITLE_KEYS = [
  'title',
  'node_name',
  'contract_no',
  'transaction_no',
  'file_name',
  'date',
  'transaction_date',
]

function arrayRowTitle(item: Record<string, unknown>, index: number) {
  for (const key of ROW_TITLE_KEYS) {
    if (item[key] !== null && item[key] !== undefined && item[key] !== '') return String(item[key])
  }
  return `记录 ${index + 1}`
}

function arrayRowMeta(item: Record<string, unknown>) {
  const title = arrayRowTitle(item, 0)
  return entries(item)
    .filter(
      ([key, value]) =>
        !isHiddenKey(key) &&
        (value === null || value === undefined || typeof value !== 'object') &&
        String(value ?? '') !== title,
    )
    .map(([key, value]) => `${label(key)} ${displayValue(key, value, item)}`)
    .join(' · ')
}

function listItemTitle(listKey: string, item: Record<string, unknown>, index: number) {
  if (listKey === 'receivables' && item.node_name) return String(item.node_name)
  return display(
    item.title ?? item.contract_no ?? item.transaction_no ?? item.file_name ?? `记录 ${index + 1}`,
  )
}

function money(value: unknown) {
  return formatCurrency(String(value))
}

function listItemMeta(listKey: string, item: Record<string, unknown>) {
  if (listKey === 'timeline') return display(item.date)
  if (listKey === 'receivables') {
    const parts: string[] = []
    if (item.due_date) parts.push(`应收日期 ${item.due_date}`)
    if (item.plan_amount !== null && item.plan_amount !== undefined && item.plan_amount !== '')
      parts.push(`计划金额 ${money(item.plan_amount)}`)
    if (item.paid_amount !== null && item.paid_amount !== undefined && item.paid_amount !== '')
      parts.push(`已收总额 ${money(item.paid_amount)}`)
    return parts.join(' · ')
  }
  return ''
}

function listItemStatus(listKey: string, item: Record<string, unknown>) {
  const status = item.status ?? item.match_status
  if (status === null || status === undefined || status === '') return null
  const text = String(status)
  if (listKey === 'allocations' || 'match_status' in item) return matchStatusLabel(text)
  if ('plan_amount' in item) return receivableStatusLabel(text)
  if ('exception_no' in item || 'exception_type' in item) return exceptionStatusLabel(text)
  if ('contract_no' in item) return contractStatusLabel(text)
  return genericStatusLabel(text)
}

function listItemTail(listKey: string, item: Record<string, unknown>) {
  if (
    listKey === 'payments' &&
    item.allocated_amount !== null &&
    item.allocated_amount !== undefined &&
    item.allocated_amount !== ''
  )
    return money(item.allocated_amount)
  const mapped = listItemStatus(listKey, item)
  if (mapped !== null) return mapped
  return display(item.amount ?? item.description)
}

function timelineTitle(item: Record<string, unknown>) {
  const action = item.action_type ?? item.action ?? item.event
  if (action === null || action === undefined || action === '') return '操作记录'
  return auditActionLabel(String(action))
}

function timelineTime(item: Record<string, unknown>) {
  const time = item.action_at ?? item.created_at
  return time === null || time === undefined || time === '' ? '' : String(time)
}

function timelineContent(item: Record<string, unknown>) {
  const content = item.action_text ?? item.detail ?? item.text
  return content === null || content === undefined || content === '' ? '' : String(content)
}

function list(value: unknown) {
  return Array.isArray(value) ? (value as Array<Record<string, unknown>>) : []
}
</script>

<template>
  <div class="business-detail">
    <template v-for="section in NAMED_SECTIONS" :key="section">
      <section
        v-if="
          isRecord(data[section]) &&
          (sectionScalars(data[section]).length > 0 || sectionArrays(data[section]).length > 0)
        "
        class="detail-section"
      >
        <h3>{{ SECTION_TITLES[section] }}</h3>
        <dl v-if="sectionScalars(data[section]).length" class="detail-grid">
          <template v-for="[key, value] in sectionScalars(data[section])" :key="key"
            ><dt>{{ label(key) }}</dt>
            <dd>{{ displayValue(key, value, recordOf(data[section])) }}</dd></template
          >
        </dl>
        <template v-for="[arrayKey, arrayValue] in sectionArrays(data[section])" :key="arrayKey">
          <p class="meta">
            <strong>{{ label(arrayKey) }}</strong>
          </p>
          <div class="mini-list">
            <div
              v-for="(item, index) in recordList(arrayValue)"
              :key="String(item.id ?? index)"
              class="mini-row"
            >
              <strong>{{ arrayRowTitle(item, index) }}</strong
              ><span v-if="arrayRowMeta(item)" class="meta">{{ arrayRowMeta(item) }}</span>
            </div>
          </div>
        </template>
      </section>
    </template>
    <section v-if="leftoverEntries(data).length" class="detail-section">
      <h3>基本信息</h3>
      <dl class="detail-grid">
        <template v-for="[key, value] in leftoverEntries(data)" :key="key"
          ><dt>{{ label(key) }}</dt>
          <dd>{{ displayValue(key, value, data) }}</dd></template
        >
      </dl>
    </section>
    <template v-for="key in LIST_KEYS" :key="key"
      ><section v-if="list(data[key]).length" class="detail-section">
        <h3>
          {{ LIST_TITLES[key] }}
        </h3>
        <div class="mini-list">
          <div
            v-for="(item, index) in list(data[key])"
            :key="String(item.id ?? index)"
            class="mini-row"
          >
            <strong>{{ listItemTitle(key, item, index) }}</strong
            ><span v-if="listItemMeta(key, item)" class="meta">{{ listItemMeta(key, item) }}</span
            ><span>{{ listItemTail(key, item) }}</span>
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
            <strong>{{ timelineTitle(item) }}</strong>
            <p v-if="timelineTime(item)" class="meta">{{ timelineTime(item) }}</p>
            <p v-if="timelineContent(item)" class="meta">{{ timelineContent(item) }}</p>
          </div>
        </li>
      </ol>
    </section>
  </div>
</template>
