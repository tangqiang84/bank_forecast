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

export async function downloadReport(baseUrl: string, token: string, tenantId: number, reportId: number) {
  const response = await fetch(`${baseUrl}/api/v1/reports/${reportId}/download`, { headers: authHeaders(token, tenantId) })
  if (!response.ok) throw new Error(`HTTP ${response.status}`)
  return response.blob()
}
