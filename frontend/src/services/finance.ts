import { authHeaders } from './auth'
import { fetchJson, fetchMultipart } from './http'

export type ApiResponse<T> = { code: number; message: string; data: T; trace_id: string }
export type ReconciliationSummary = {
  job_id: number
  status: string
  matched: number
  bank_unrecorded: number
  finance_unmatched: number
  date_from: string | null
  date_to: string | null
}
export type ReconciliationResult = {
  id: number
  exception_no: string
  exception_type: 'bank_unrecorded' | 'finance_unmatched'
  source_type: string
  source_id: number
  title: string
  description: string
  status: string
  severity: string
  transaction_no: string | null
  transaction_date: string | null
  bank_amount: string | null
  record_no: string | null
  record_date: string | null
  finance_amount: string | null
}
export type ReconciliationPage = { items: ReconciliationResult[]; page: number; page_size: number; total: number }

export function importFinanceRecords(baseUrl: string, token: string, tenantId: number, file: File) {
  const formData = new FormData()
  formData.append('file', file)
  return fetchMultipart<ApiResponse<Record<string, unknown>>>(`${baseUrl}/api/v1/imports/finance-records`, formData, token, tenantId)
}

export function runReconciliation(baseUrl: string, token: string, tenantId: number, filters: Record<string, string> = {}) {
  const params = new URLSearchParams()
  Object.entries(filters).forEach(([key, value]) => { if (value) params.set(key, value) })
  return fetchJson<ApiResponse<ReconciliationSummary>>(`${baseUrl}/api/v1/reconciliation/run?${params.toString()}`, { method: 'POST', headers: authHeaders(token, tenantId) })
}

export function loadReconciliationResults(baseUrl: string, token: string, tenantId: number, jobId?: number) {
  const params = new URLSearchParams({ page: '1', page_size: '100' })
  if (jobId) params.set('job_id', String(jobId))
  return fetchJson<ApiResponse<ReconciliationPage>>(`${baseUrl}/api/v1/reconciliation/results?${params.toString()}`, { headers: authHeaders(token, tenantId) })
}
