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

// 与 backend `auditService.record(` 全部动作码一致（2026-09-30 核对，共 62 个）；
// 末尾 ASSIGN/COMMENT/RESOLVE/CLOSE/FALSE_POSITIVE 为异常处理日志 exception_action_log.action_type。
export const AUDIT_ACTION_LABELS: Record<string, string> = {
  ACTIVATE_FORECAST_MODEL: '启用预测模型',
  APPLY_INDUSTRY_TEMPLATE: '应用行业模板',
  ASSIGN_EXCEPTION: '分派异常',
  BACKFILL_FORECAST_ACTUALS: '回填实际金额',
  BATCH_CLASSIFY_BANK_TRANSACTION: '批量分类流水',
  BATCH_UNLINK_BANK_TRANSACTION: '批量解除流水关联',
  BATCH_UPDATE_PROJECT_STATUS: '批量更新项目状态',
  CLASSIFY_BANK_TRANSACTION: '分类流水',
  CLOSE_BANK_ACCOUNT: '账户销户',
  CLOSE_EXCEPTION: '关闭异常',
  COMMENT_EXCEPTION: '异常备注',
  CONFIRM_BANK_STATEMENT: '确认银行流水入账',
  CONFIRM_CONTRACT: '确认合同导入',
  CONFIRM_FINANCE_RECORD: '确认财务记录导入',
  CONFIRM_MATCH_RESULT: '确认匹配结果',
  CONFIRM_PROJECT: '确认项目导入',
  CONFIRM_RECEIPT: '确认回单导入',
  CREATE_BANK_ACCOUNT: '新建银行账户',
  CREATE_BANK_CONNECTION: '新建接入配置',
  CREATE_REPORT: '生成报表',
  CREATE_USER: '新建用户',
  DELETE_BANK_CONNECTION: '删除接入配置',
  DELETE_EXCEPTION_ATTACHMENT: '删除异常附件',
  DOWNLOAD_EXCEPTION_ATTACHMENT: '下载异常附件',
  DOWNLOAD_REPORT: '下载报表',
  FALSE_POSITIVE_EXCEPTION: '标记误报',
  IMPORT_BANK_STATEMENT: '导入银行流水',
  IMPORT_CONTRACT: '导入合同',
  IMPORT_FINANCE_RECORD: '导入财务记录',
  IMPORT_PROJECT: '导入项目',
  IMPORT_RECEIPT: '导入回单',
  PREVIEW_BANK_STATEMENT: '预览银行流水',
  PREVIEW_CONTRACT: '预览合同',
  PREVIEW_FINANCE_RECORD: '预览财务记录',
  PREVIEW_PROJECT: '预览项目',
  PREVIEW_RECEIPT: '预览回单',
  PRINT_REPORT: '打印预览',
  REJECT_MATCH_RESULT: '拒绝匹配结果',
  RESET_USER_PASSWORD: '重置用户密码',
  RESOLVE_EXCEPTION: '处理完成异常',
  RETRY_BANK_STATEMENT_ERRORS: '重试流水失败行',
  RETRY_CONTRACT_ERRORS: '重试合同失败行',
  RETRY_FINANCE_RECORD_ERRORS: '重试财务记录失败行',
  RETRY_PROJECT_ERRORS: '重试项目失败行',
  RETRY_RECEIPT_ERRORS: '重试回单失败行',
  ROLLBACK_PROJECT_RISK_RULE: '回滚风险规则',
  RUN_FINANCE_RECONCILIATION: '运行财务对账',
  RUN_FORECAST: '运行现金预测',
  RUN_RECEIVABLE_MATCH: '运行回款匹配',
  SCAN_IDLE_BANK_ACCOUNTS: '盘点闲置账户',
  TEST_BANK_CONNECTION: '测试接入连接',
  UNLINK_BANK_TRANSACTION: '解除流水关联',
  UPDATE_BANK_ACCOUNT: '更新银行账户',
  UPDATE_BANK_CONNECTION: '更新接入配置',
  UPDATE_PROJECT: '更新项目',
  UPDATE_PROJECT_RISK_RULE: '更新风险规则',
  UPDATE_PROJECT_SCOPE: '更新项目数据范围',
  UPDATE_ROLE_PERMISSIONS: '更新角色权限',
  UPDATE_RULE_CONFIG: '更新规则配置',
  UPDATE_USER: '更新用户',
  UPLOAD_EXCEPTION_ATTACHMENT: '上传异常附件',
  UPLOAD_RECEIPT_IMAGE: '上传回单影像',
  VIEW_RECEIPT_IMAGE: '查看回单影像',
  ASSIGN: '分派',
  COMMENT: '备注',
  RESOLVE: '处理完成',
  CLOSE: '关闭',
  FALSE_POSITIVE: '标记误报',
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
  bank_code: '银行代码',
  bank_name: '开户银行',
  account_name: '账户名称',
  account_no_last4: '账号后四位',
  currency: '币种',
  current_balance: '当前余额',
  last_transaction_at: '最近动账时间',
  idle_days: '闲置天数',
  idle_level: '闲置等级',
  transaction_count: '交易笔数',
  booking_date: '记账日期',
  counterparty_account_cipher: '对方账号',
  purpose: '用途',
  category: '分类',
  contract_name: '合同名称',
  project_no: '项目编号',
  project_manager: '项目经理',
  project_status: '项目状态',
  contract_amount: '合同金额',
  sign_date: '签订日期',
  start_date: '开始日期',
  end_date: '结束日期',
  delivery_date: '交付日期',
  acceptance_date: '验收日期',
  remark: '备注',
  receivable_amount: '应收总额',
  paid_amount: '已收总额',
  overdue_amount: '逾期金额',
  exception_count: '异常数量',
  cashflow_amount: '净现金流',
  paid_in_amount: '流入金额',
  paid_out_amount: '流出金额',
  paid_rate: '回款率',
  overdue_rate: '逾期率',
  risk_score: '风险评分',
  risk_factors: '风险因子明细',
  risk_items: '风险项',
  title: '标题',
  description: '描述',
  owner_user_id: '处理人',
  closed_at: '关闭时间',
  created_at: '创建时间',
  updated_at: '更新时间',
  started_at: '开始时间',
  confirmed_by: '确认人',
  confirmed_at: '确认时间',
  report_type: '报表类型',
  date_from: '开始日期',
  date_to: '结束日期',
  file_name: '文件名',
  error_message: '错误信息',
  finance_subject: '财务科目',
  match_group_id: '匹配组',
  allocated_amount: '分配金额',
  plan_amount: '计划金额',
  node_name: '节点名称',
  node_type: '节点类型',
  match_reason: '匹配理由',
  last_test_status: '最近测试',
  last_test_message: '测试信息',
  last_tested_at: '最近测试时间',
  bank_account_id: '关联账户',
  transaction_no: '流水号',
  transaction_date: '交易日期',
  direction: '方向',
  amount: '金额',
  balance_after: '余额',
  counterparty_name: '对方户名',
  summary: '摘要',
  status: '状态',
  match_status: '匹配状态',
  transaction_match_status: '流水匹配状态',
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
  // 报表 result 指标，与 backend ReportService METRIC_LABELS 一致
  total_balance: '全账户总余额',
  income_total: '收入金额',
  expense_total: '支出金额',
  net_cashflow: '净流入',
  receivable_plan_total: '应收总额',
  receivable_paid_total: '已收总额',
  overdue_receivable_total: '逾期未收',
  pending_exception_count: '待处理异常',
  match_rate: '匹配率',
  health_score: '健康评分',
  health_level: '健康等级',
  receivable_due_in_week: '本周到期应收',
  confirmed_receipts_in_week: '本周确认回款',
  active_account_total: '账户总数',
  active_accounts_in_week: '本周动账账户',
  exceptions_closed_in_week: '本周闭环异常',
  report_name: '报表名称',
  daily_breakdown: '逐日明细',
  count: '数量',
}

export function fieldNameLabel(key: string): string {
  return FIELD_NAME_LABELS[key] ?? key
}
