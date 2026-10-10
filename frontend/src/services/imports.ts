import { authHeaders } from './auth'
import { fetchBlob, fetchJson } from './http'

export type ApiResponse<T> = { code: number; message: string; data: T; trace_id: string }

export type ImportJobType =
  'bank_statement' | 'contract' | 'finance_record' | 'project' | 'receipt' | 'payable'

// duplicate_suspect：弱指纹疑似重复行，默认不随确认入账，需显式 include_duplicates。
export type ImportRowStatus =
  'valid' | 'failed' | 'skipped' | 'retry_success' | 'confirmed' | 'success' | 'duplicate_suspect'

export type GenericImportRow<P> = {
  id: number
  row_no: number
  status: ImportRowStatus | (string & {})
  error_message: string | null
  payload: P | null
}

export type ImportErrorDetail = {
  row_no: number
  field_name: string | null
  raw_json: string | null
  error_message: string
}

export type GenericImportPreview<P> = {
  job_id: number
  job_type: ImportJobType | string
  status: string
  total_rows: number
  success_rows: number
  failed_rows: number
  skipped_rows: number
  duplicate_suspected_rows?: number
  preview_rows: Array<GenericImportRow<P>>
  error_details: ImportErrorDetail[]
  recognized_templates?: Array<{
    sheet_name: string
    bank_name?: string
    status: string
    message: string
  }>
}

export type ImportRetryRow = { row_no: number } & Record<string, string | number | null>

// 任务详情页跨任务类型查看，payload 键集合随 job_type 变化；
// 银行流水任务兼容历史扁平字段。
export type ImportJobPreviewRow = GenericImportRow<Record<string, string | null>> & {
  transaction_no?: string | null
  transaction_date?: string | null
  direction?: string | null
  amount?: string | null
}

export type ImportJobPreview = Omit<
  GenericImportPreview<Record<string, string | null>>,
  'preview_rows'
> & {
  preview_rows: ImportJobPreviewRow[]
}

export function loadImportJobPreview(
  baseUrl: string,
  token: string,
  tenantId: number,
  jobId: number,
) {
  return fetchJson<ApiResponse<ImportJobPreview>>(`${baseUrl}/api/v1/imports/${jobId}/preview`, {
    headers: authHeaders(token, tenantId),
  })
}

export function confirmImportJob<P = Record<string, string | null>>(
  baseUrl: string,
  token: string,
  tenantId: number,
  jobId: number,
  includeDuplicates?: number[],
) {
  const withDuplicates = includeDuplicates !== undefined && includeDuplicates.length > 0
  return fetchJson<ApiResponse<GenericImportPreview<P>>>(
    `${baseUrl}/api/v1/imports/${jobId}/confirm`,
    {
      method: 'POST',
      headers: withDuplicates
        ? { ...authHeaders(token, tenantId), 'Content-Type': 'application/json' }
        : authHeaders(token, tenantId),
      body: withDuplicates ? JSON.stringify({ include_duplicates: includeDuplicates }) : undefined,
    },
  )
}

export type ImportQualitySummary = {
  days: number
  job_count: number
  total_rows: number
  success_rows: number
  failed_rows: number
  skipped_rows: number
  duplicate_suspected_rows: number
  success_rate: number | null
  duplicate_suspect_rate: number | null
}

export function loadImportQualitySummary(
  baseUrl: string,
  token: string,
  tenantId: number,
  days = 30,
) {
  return fetchJson<ApiResponse<ImportQualitySummary>>(
    `${baseUrl}/api/v1/imports/quality-summary?days=${days}`,
    { headers: authHeaders(token, tenantId) },
  )
}

export function retryImportJobErrors<P = Record<string, string | null>>(
  baseUrl: string,
  token: string,
  tenantId: number,
  jobId: number,
  rows: ImportRetryRow[],
) {
  return fetchJson<ApiResponse<GenericImportPreview<P>>>(
    `${baseUrl}/api/v1/imports/${jobId}/retry-errors`,
    {
      method: 'POST',
      headers: { ...authHeaders(token, tenantId), 'Content-Type': 'application/json' },
      body: JSON.stringify({ rows }),
    },
  )
}

export function downloadImportJobErrors(
  baseUrl: string,
  token: string,
  tenantId: number,
  jobId: number,
) {
  return fetchBlob(`${baseUrl}/api/v1/imports/${jobId}/errors/download`, {
    headers: authHeaders(token, tenantId),
  })
}
