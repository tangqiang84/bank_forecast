import { directionLabel, payableStatusLabel, projectStatusLabel, recordTypeLabel } from './labels'

// 导入预览表格共享定义：统一导入向导 /imports（P6-3）与导入任务详情页 /imports/:jobId 共用，
// 列口径与取值逻辑保持与历史 ImportJobView 一致。

export type PreviewColumn = { key: string; label: string }

// 预览行结构：payload 按 job_type 变化；银行流水兼容历史扁平字段。
export type PreviewTableRow = {
  payload?: Record<string, string | null> | null
  transaction_no?: string | null
  transaction_date?: string | null
  direction?: string | null
  amount?: string | null
}

// 向导页统一预览结构：六类 preview 端点响应均可直接赋值（银行流水响应无 payload/无 job_type）。
export type WizardPreviewRow = PreviewTableRow & {
  id: number
  row_no: number
  status: string
  error_message: string | null
}

export type WizardPreview = {
  job_id: number
  status: string
  total_rows: number
  success_rows: number
  failed_rows: number
  skipped_rows: number
  duplicate_suspected_rows?: number
  preview_rows: WizardPreviewRow[]
}

export const IMPORT_PREVIEW_COLUMNS: Record<string, PreviewColumn[]> = {
  bank_statement: [
    { key: 'transaction_no', label: '流水号' },
    { key: 'transaction_date', label: '日期' },
    { key: 'direction', label: '方向' },
    { key: 'amount', label: '金额' },
  ],
  contract: [
    { key: 'contract_no', label: '合同编号' },
    { key: 'contract_name', label: '合同名称' },
    { key: 'customer_name', label: '客户' },
    { key: 'node_name', label: '节点' },
    { key: 'due_date', label: '应收日期' },
    { key: 'plan_amount', label: '计划金额' },
  ],
  finance_record: [
    { key: 'record_no', label: '记录编号' },
    { key: 'record_type', label: '类型' },
    { key: 'record_date', label: '记录日期' },
    { key: 'counterparty_name', label: '对手方' },
    { key: 'amount', label: '金额' },
  ],
  project: [
    { key: 'project_no', label: '项目编号' },
    { key: 'project_name', label: '项目名称' },
    { key: 'customer_name', label: '客户' },
    { key: 'project_manager', label: '负责人' },
    { key: 'project_status', label: '状态' },
    { key: 'start_date', label: '开始日期' },
    { key: 'delivery_date', label: '交付日期' },
    { key: 'acceptance_date', label: '验收日期' },
  ],
  receipt: [
    { key: 'receipt_no', label: '回单号' },
    { key: 'transaction_date', label: '交易日期' },
    { key: 'payer_name', label: '付款方' },
    { key: 'payee_name', label: '收款方' },
    { key: 'amount', label: '金额' },
    { key: 'transaction_no', label: '关联流水号' },
  ],
  payable: [
    { key: 'supplier_name', label: '供应商' },
    { key: 'node_name', label: '节点' },
    { key: 'due_date', label: '应付日期' },
    { key: 'plan_amount', label: '应付金额' },
    { key: 'paid_amount', label: '已付金额' },
    { key: 'status', label: '状态' },
  ],
}

export function previewColumnsFor(jobType: string): PreviewColumn[] {
  return IMPORT_PREVIEW_COLUMNS[jobType] ?? []
}

const IMPORT_PREVIEW_VALUE_LABELS: Record<string, (value: string | null | undefined) => string> = {
  direction: directionLabel,
  record_type: recordTypeLabel,
  project_status: projectStatusLabel,
  status: payableStatusLabel,
}

export function previewFieldValue(row: PreviewTableRow, key: string): string {
  const flatFields: Record<string, string | null | undefined> = {
    transaction_no: row.transaction_no,
    transaction_date: row.transaction_date,
    direction: row.direction,
    amount: row.amount,
  }
  const raw = row.payload?.[key] ?? flatFields[key]
  if (raw === null || raw === undefined || raw === '') return '-'
  const mapper = IMPORT_PREVIEW_VALUE_LABELS[key]
  return mapper ? mapper(raw) : raw
}

// 导入质量汇总比率展示：backend 未统计时返回 null。
export function rateText(value: number | null): string {
  return value === null ? '暂无数据' : `${value}%`
}
