type LabelValue = string | null | undefined

function fromMap(map: Record<string, string>, value: LabelValue): string {
  if (value === null || value === undefined || value === '') return '-'
  return map[value] ?? value
}

export const ACCOUNT_STATUS_LABELS: Record<string, string> = {
  active: '活跃',
  low_frequency: '低频',
  idle: '闲置',
  pending_close: '待销户',
  closed: '已销户',
}

export function accountStatusLabel(value: LabelValue): string {
  return fromMap(ACCOUNT_STATUS_LABELS, value)
}

export const IDLE_LEVEL_LABELS: Record<string, string> = {
  normal: '正常',
  idle_30: '30 天未动账',
  idle_90: '90 天未动账',
  idle_180: '180 天未动账',
}

export function idleLevelLabel(value: LabelValue): string {
  return fromMap(IDLE_LEVEL_LABELS, value)
}

export const DIRECTION_LABELS: Record<string, string> = {
  income: '收入',
  expense: '支出',
  transfer: '内部转账',
  refund: '退款',
  reversal: '冲正',
}

export function directionLabel(value: LabelValue): string {
  return fromMap(DIRECTION_LABELS, value)
}

// match_result.match_status 实际值域以 backend 为准：suggested/matched/confirmed/rejected/unlinked；
// bank_transaction.match_status 使用 unmatched/suggested/matched/manual_confirmed。
export const MATCH_STATUS_LABELS: Record<string, string> = {
  unmatched: '未匹配',
  suggested: '待确认',
  matched: '已自动匹配',
  manual_confirmed: '已人工确认',
  confirmed: '已确认',
  rejected: '已拒绝',
  unlinked: '已解除关联',
}

export function matchStatusLabel(value: LabelValue): string {
  return fromMap(MATCH_STATUS_LABELS, value)
}

// backend 实际还会产出 partial（部分金额）和 finance_reconcile（财务对账）。
export const MATCH_TYPE_LABELS: Record<string, string> = {
  exact: '精确匹配',
  amount_date: '金额日期匹配',
  similar: '相似匹配',
  split: '拆分匹配',
  merge: '合并匹配',
  partial: '部分金额匹配',
  finance_reconcile: '财务对账匹配',
}

export function matchTypeLabel(value: LabelValue): string {
  return fromMap(MATCH_TYPE_LABELS, value)
}

export const CONFIDENCE_LEVEL_LABELS: Record<string, string> = {
  high: '高',
  medium: '中',
  low: '低',
}

export function confidenceLevelLabel(value: LabelValue): string {
  return fromMap(CONFIDENCE_LEVEL_LABELS, value)
}

export const ALLOCATION_MODE_LABELS: Record<string, string> = {
  single: '单笔',
  multi: '多笔合计',
}

export function allocationModeLabel(value: LabelValue): string {
  return fromMap(ALLOCATION_MODE_LABELS, value)
}

// backend 实际写入 unpaid/partial/paid，文档口径 pending/partial_received/received/overdue/closed 一并保留。
export const RECEIVABLE_STATUS_LABELS: Record<string, string> = {
  pending: '未到期',
  partial_received: '部分收款',
  received: '已收齐',
  overdue: '逾期未收',
  closed: '已关闭',
  unpaid: '未收款',
  partial: '部分收款',
  paid: '已收齐',
}

export function receivableStatusLabel(value: LabelValue): string {
  return fromMap(RECEIVABLE_STATUS_LABELS, value)
}

export const PROJECT_STATUS_LABELS: Record<string, string> = {
  active: '进行中',
  paused: '已暂停',
  completed: '已完成',
  cancelled: '已取消',
}

export function projectStatusLabel(value: LabelValue): string {
  return fromMap(PROJECT_STATUS_LABELS, value)
}

export const RISK_LEVEL_LABELS: Record<string, string> = {
  high: '高',
  medium: '中',
  low: '低',
}

export function riskLevelLabel(value: LabelValue): string {
  return fromMap(RISK_LEVEL_LABELS, value)
}

export const SEVERITY_LABELS = RISK_LEVEL_LABELS

export function severityLabel(value: LabelValue): string {
  return fromMap(SEVERITY_LABELS, value)
}

export const EXCEPTION_STATUS_LABELS: Record<string, string> = {
  new: '新发现',
  in_progress: '处理中',
  resolved: '已解决',
  closed: '已关闭',
  false_positive: '误报关闭',
}

export function exceptionStatusLabel(value: LabelValue): string {
  return fromMap(EXCEPTION_STATUS_LABELS, value)
}

// 文档口径六类之外，backend 实际产出 unreceived/partial_receipt/unknown_receipt
// 以及对账差异类 timing_difference/bank_unrecorded/finance_unmatched。
export const EXCEPTION_TYPE_LABELS: Record<string, string> = {
  receivable_unreceived: '应收未收',
  payable_unpaid: '应付未付',
  transaction_missing: '流水缺失',
  transaction_duplicate: '流水重复',
  rule_hit: '规则命中异常',
  manual_exception: '人工创建异常',
  unreceived: '应收未收',
  partial_receipt: '部分收款',
  unknown_receipt: '未知收款',
  timing_difference: '跨月时间差',
  bank_unrecorded: '银行未记账',
  finance_unmatched: '财务未在银行发生',
}

export function exceptionTypeLabel(value: LabelValue): string {
  return fromMap(EXCEPTION_TYPE_LABELS, value)
}

export const IMPORT_JOB_STATUS_LABELS: Record<string, string> = {
  pending: '待处理',
  running: '处理中',
  preview_pending: '待确认预览',
  preview_failed: '预览校验失败',
  success: '成功',
  partial_success: '部分成功',
  failed: '失败',
}

export function importJobStatusLabel(value: LabelValue): string {
  return fromMap(IMPORT_JOB_STATUS_LABELS, value)
}

// 银行流水预览行（import_preview_row）确认后由 backend 写为 confirmed/success。
export const IMPORT_ROW_STATUS_LABELS: Record<string, string> = {
  valid: '有效',
  failed: '失败',
  skipped: '跳过',
  retry_success: '重试成功',
  confirmed: '已确认',
  success: '成功',
}

export function importRowStatusLabel(value: LabelValue): string {
  return fromMap(IMPORT_ROW_STATUS_LABELS, value)
}

export const JOB_TYPE_LABELS: Record<string, string> = {
  bank_statement: '银行流水导入',
  contract: '合同导入',
  project: '项目导入',
  finance_record: '财务记录导入',
  receipt: '回单处理',
  match: '匹配任务',
  report: '报表任务',
}

export function jobTypeLabel(value: LabelValue): string {
  return fromMap(JOB_TYPE_LABELS, value)
}

export const RECORD_TYPE_LABELS: Record<string, string> = {
  receipt: '收款单',
  payment: '付款单',
  voucher: '凭证',
  journal: '日记账',
}

export function recordTypeLabel(value: LabelValue): string {
  return fromMap(RECORD_TYPE_LABELS, value)
}

export const REPORT_TYPE_LABELS: Record<string, string> = {
  daily: '日报',
  weekly: '周报',
  monthly: '月报',
  health: '资金体检',
  custom: '自定义报表',
}

export function reportTypeLabel(value: LabelValue): string {
  return fromMap(REPORT_TYPE_LABELS, value)
}

export const TASK_STATUS_LABELS: Record<string, string> = {
  pending: '待处理',
  running: '处理中',
  success: '成功',
  failed: '失败',
}

export function taskStatusLabel(value: LabelValue): string {
  return fromMap(TASK_STATUS_LABELS, value)
}

export const reportTaskStatusLabel = taskStatusLabel
export const forecastTaskStatusLabel = taskStatusLabel

export const TEMPLATE_RECOGNITION_STATUS_LABELS: Record<string, string> = {
  recognized: '已识别',
  unsupported: '未识别',
}

export function templateRecognitionStatusLabel(value: LabelValue): string {
  return fromMap(TEMPLATE_RECOGNITION_STATUS_LABELS, value)
}

export const CONNECTION_TYPE_LABELS: Record<string, string> = {
  file: '文件导入',
  rpa: 'RPA 接入',
  direct_connect: '直连',
}

export function connectionTypeLabel(value: LabelValue): string {
  return fromMap(CONNECTION_TYPE_LABELS, value)
}

export const USER_STATUS_LABELS: Record<string, string> = {
  active: '启用',
  disabled: '停用',
}

export function userStatusLabel(value: LabelValue): string {
  return fromMap(USER_STATUS_LABELS, value)
}

export const HEALTH_LEVEL_LABELS: Record<string, string> = {
  healthy: '健康',
  warning: '关注',
  danger: '风险',
}

export function healthLevelLabel(value: LabelValue): string {
  return fromMap(HEALTH_LEVEL_LABELS, value)
}

export const MODEL_NAME_LABELS: Record<string, string> = {
  'moving-average-with-trend': '移动平均（含趋势）',
}

export function modelNameLabel(value: LabelValue): string {
  return fromMap(MODEL_NAME_LABELS, value)
}

export const AUDIT_ACTION_LABELS: Record<string, string> = {
  CREATE_REPORT: '生成报表',
  DOWNLOAD_REPORT: '下载报表',
  PRINT_REPORT: '打印预览',
}

export function auditActionLabel(value: LabelValue): string {
  return fromMap(AUDIT_ACTION_LABELS, value)
}

export const INDUSTRY_TEMPLATE_LABELS: Record<string, string> = {
  it_software: 'IT 软件与信息服务',
}

export function industryTemplateLabel(value: LabelValue): string {
  return fromMap(INDUSTRY_TEMPLATE_LABELS, value)
}

// 权限模块全集与 backend permission 表一致；rule 为预留模块名。
export const PERMISSION_MODULE_LABELS: Record<string, string> = {
  dashboard: '驾驶舱',
  account: '账户',
  transaction: '流水',
  import: '导入',
  contract: '合同',
  project: '项目',
  matching: '匹配',
  exception: '异常',
  attachment: '附件',
  reconciliation: '对账',
  report: '报表',
  forecast: '预测',
  audit: '审计',
  receipt: '回单',
  system: '系统管理',
  rule: '规则',
}

export function permissionModuleLabel(value: LabelValue): string {
  return fromMap(PERMISSION_MODULE_LABELS, value)
}

export const RULE_STATUS_LABELS: Record<string, string> = {
  enabled: '启用',
  disabled: '禁用',
}

export function ruleStatusLabel(value: LabelValue): string {
  return fromMap(RULE_STATUS_LABELS, value)
}

export const CONTRACT_STATUS_LABELS: Record<string, string> = {
  active: '生效中',
  closed: '已关闭',
  disabled: '已停用',
}

export function contractStatusLabel(value: LabelValue): string {
  return fromMap(CONTRACT_STATUS_LABELS, value)
}

// 通用 status 兜底：按异常、导入任务、应收计划、账户、用户、项目、规则的顺序命中。
const GENERIC_STATUS_MAPS = [
  EXCEPTION_STATUS_LABELS,
  IMPORT_JOB_STATUS_LABELS,
  RECEIVABLE_STATUS_LABELS,
  MATCH_STATUS_LABELS,
  TASK_STATUS_LABELS,
  ACCOUNT_STATUS_LABELS,
  USER_STATUS_LABELS,
  PROJECT_STATUS_LABELS,
  RULE_STATUS_LABELS,
  CONTRACT_STATUS_LABELS,
]

export function genericStatusLabel(value: LabelValue): string {
  if (value === null || value === undefined || value === '') return '-'
  for (const map of GENERIC_STATUS_MAPS) {
    if (Object.prototype.hasOwnProperty.call(map, value)) return map[value]
  }
  return value
}

export const FIELD_NAME_LABELS: Record<string, string> = {
  transaction_no: '流水号',
  transaction_date: '交易日期',
  direction: '方向',
  amount: '金额',
  balance_after: '余额',
  counterparty_name: '对方户名',
  summary: '摘要',
  status: '状态',
  match_status: '匹配状态',
  contract_no: '合同编号',
  project_name: '项目名称',
  customer_name: '客户名称',
  exception_type: '异常类型',
  severity: '严重级别',
  due_date: '到期日期',
  exception_no: '异常编号',
  source_type: '来源类型',
  source_id: '来源编号',
  record_no: '财务单号',
  record_date: '财务日期',
  bank_amount: '银行金额',
  finance_amount: '财务金额',
  forecast_date: '预测日期',
  forecast_amount: '预测净现金流',
  expected_receivable: '预计应收',
  projected_balance: '预计余额',
  actual_amount: '实际金额',
  deviation_amount: '偏差',
  risk_level: '风险等级',
  risk_message: '风险说明',
  job_id: '任务编号',
  model_name: '模型名称',
  model_version: '模型版本',
  horizon: '预测天数',
  window_size: '历史窗口',
  attempt_count: '尝试次数',
  input_start_date: '输入开始日期',
  input_end_date: '输入结束日期',
  finished_at: '完成时间',
  evaluated_points: '评估点数',
  mae: 'MAE',
  rmse: 'RMSE',
  mean_deviation: '平均偏差',
}

export function fieldNameLabel(key: string): string {
  return FIELD_NAME_LABELS[key] ?? key
}
