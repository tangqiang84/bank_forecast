import { authHeaders } from './auth'
import { fetchJson } from './http'

export type ApiResponse<T> = { code: number; message: string; data: T; trace_id: string }
export type Project = {
  id: number
  project_no: string
  project_name: string
  customer_name: string | null
  project_manager: string | null
  project_status: string
  contract_amount: string
  receivable_amount: string
  paid_amount: string
  overdue_amount: string
  contract_count: number
  exception_count: number
  paid_rate: number
  risk_score: number
  risk_level: string
  risk_items: string[]
}
export type ProjectPage = { items: Project[]; page: number; page_size: number; total: number }
export function loadProjects(
  baseUrl: string,
  token: string,
  tenantId: number,
  page = 1,
  pageSize = 20,
) {
  return fetchJson<ApiResponse<ProjectPage>>(
    `${baseUrl}/api/v1/projects?page=${page}&page_size=${pageSize}`,
    { headers: authHeaders(token, tenantId) },
  )
}
export function loadProjectDetail(baseUrl: string, token: string, tenantId: number, id: number) {
  return fetchJson<ApiResponse<Record<string, unknown>>>(`${baseUrl}/api/v1/projects/${id}`, {
    headers: authHeaders(token, tenantId),
  })
}
export type ProjectRiskRule = {
  id: number
  rule_code: string
  threshold: string
  penalty: string
  max_penalty: string | null
  enabled: boolean
  updated_at: string
}
export function updateProject(
  baseUrl: string,
  token: string,
  tenantId: number,
  id: number,
  payload: Record<string, unknown>,
) {
  return fetchJson<ApiResponse<Record<string, unknown>>>(`${baseUrl}/api/v1/projects/${id}`, {
    method: 'PUT',
    headers: { ...authHeaders(token, tenantId), 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
}
export function batchUpdateProjectStatus(
  baseUrl: string,
  token: string,
  tenantId: number,
  projectIds: number[],
  projectStatus: string,
) {
  return fetchJson<ApiResponse<{ updated: number }>>(`${baseUrl}/api/v1/projects/batch-status`, {
    method: 'POST',
    headers: { ...authHeaders(token, tenantId), 'Content-Type': 'application/json' },
    body: JSON.stringify({ project_ids: projectIds, project_status: projectStatus }),
  })
}
export function loadProjectRiskRules(baseUrl: string, token: string, tenantId: number) {
  return fetchJson<ApiResponse<ProjectRiskRule[]>>(`${baseUrl}/api/v1/projects/risk-rules`, {
    headers: authHeaders(token, tenantId),
  })
}
export function updateProjectRiskRule(
  baseUrl: string,
  token: string,
  tenantId: number,
  ruleCode: string,
  payload: Record<string, unknown>,
) {
  return fetchJson<ApiResponse<ProjectRiskRule>>(
    `${baseUrl}/api/v1/projects/risk-rules/${encodeURIComponent(ruleCode)}`,
    {
      method: 'PUT',
      headers: { ...authHeaders(token, tenantId), 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    },
  )
}
