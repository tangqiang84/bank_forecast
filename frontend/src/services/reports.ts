import { authHeaders } from './auth'
import { fetchJson } from './http'

export type ApiResponse<T> = { code: number; message: string; data: T; trace_id: string }
export type ReportTask = {
  id: number
  report_type: string
  date_from: string | null
  date_to: string | null
  status: string
  file_name: string | null
  error_message: string | null
  created_at: string
  updated_at: string
  result?: Record<string, unknown>
}

export type ReportAuditLog = {
  id: number
  user_id: number | null
  action: string
  target_type: string
  target_id: string | null
  trace_id: string
  detail: string | null
  created_at: string
}

export function createReport(baseUrl: string, token: string, tenantId: number, reportType: string, params: Record<string, string>) {
  return fetchJson<ApiResponse<ReportTask>>(`${baseUrl}/api/v1/reports`, {
    method: 'POST',
    headers: { ...authHeaders(token, tenantId), 'Content-Type': 'application/json' },
    body: JSON.stringify({ report_type: reportType, params_json: params }),
  })
}

export function loadReports(baseUrl: string, token: string, tenantId: number) {
  return fetchJson<ApiResponse<ReportTask[]>>(`${baseUrl}/api/v1/reports`, { headers: authHeaders(token, tenantId) })
}

export function loadReportDetail(baseUrl: string, token: string, tenantId: number, reportId: number) {
  return fetchJson<ApiResponse<ReportTask>>(`${baseUrl}/api/v1/reports/${reportId}`, { headers: authHeaders(token, tenantId) })
}

export function loadReportAudits(baseUrl: string, token: string, tenantId: number, reportId: number) {
  const params = new URLSearchParams({ target_type: 'report_task', target_id: String(reportId), page_size: '20' })
  return fetchJson<ApiResponse<{ items: ReportAuditLog[]; total: number }>>(`${baseUrl}/api/v1/audit-logs?${params.toString()}`, { headers: authHeaders(token, tenantId) })
}

export async function downloadReport(baseUrl: string, token: string, tenantId: number, reportId: number) {
  const response = await fetch(`${baseUrl}/api/v1/reports/${reportId}/download`, { headers: authHeaders(token, tenantId) })
  if (!response.ok) throw new Error(`HTTP ${response.status}`)
  return response.blob()
}
