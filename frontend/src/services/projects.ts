import { authHeaders } from './auth'
import { fetchJson } from './http'

export type ApiResponse<T> = { code: number; message: string; data: T; trace_id: string }
export type Project = {
  id: number; project_no: string; project_name: string; customer_name: string | null; project_manager: string | null; project_status: string
  contract_amount: string; receivable_amount: string; paid_amount: string; overdue_amount: string; contract_count: number; exception_count: number
  paid_rate: number; risk_score: number; risk_level: string; risk_items: string[]
}
export type ProjectPage = { items: Project[]; page: number; page_size: number; total: number }
export function loadProjects(baseUrl: string, token: string, tenantId: number) { return fetchJson<ApiResponse<ProjectPage>>(`${baseUrl}/api/v1/projects?page=1&page_size=100`, { headers: authHeaders(token, tenantId) }) }
export function loadProjectDetail(baseUrl: string, token: string, tenantId: number, id: number) { return fetchJson<ApiResponse<Record<string, unknown>>>(`${baseUrl}/api/v1/projects/${id}`, { headers: authHeaders(token, tenantId) }) }
