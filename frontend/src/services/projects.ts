import { authHeaders } from './auth'
import { fetchJson, fetchMultipart } from './http'
import type { GenericImportPreview } from './imports'

export type ApiResponse<T> = { code: number; message: string; data: T; trace_id: string }
export type Project = {
  id: number
  project_no: string
  project_name: string
  customer_name: string | null
  project_manager: string | null
  project_status: string
  start_date: string | null
  delivery_date: string | null
  acceptance_date: string | null
  remark: string | null
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

export type ProjectImportPayload = {
  project_no: string | null
  project_name: string | null
  customer_name: string | null
  project_manager: string | null
  project_status: string | null
  start_date: string | null
  delivery_date: string | null
  acceptance_date: string | null
  remark: string | null
}

export function previewProjects(baseUrl: string, token: string, tenantId: number, file: File) {
  const formData = new FormData()
  formData.append('file', file)
  return fetchMultipart<ApiResponse<GenericImportPreview<ProjectImportPayload>>>(
    `${baseUrl}/api/v1/imports/projects/preview`,
    formData,
    token,
    tenantId,
  )
}

export function importProjects(baseUrl: string, token: string, tenantId: number, file: File) {
  const formData = new FormData()
  formData.append('file', file)
  return fetchMultipart<ApiResponse<Record<string, unknown>>>(
    `${baseUrl}/api/v1/imports/projects`,
    formData,
    token,
    tenantId,
  )
}
export type ProjectPage = { items: Project[]; page: number; page_size: number; total: number }
export function loadProjects(
  baseUrl: string,
  token: string,
  tenantId: number,
  page = 1,
  pageSize = 20,
  filters: Record<string, string> = {},
) {
  const params = new URLSearchParams({ page: String(page), page_size: String(pageSize) })
  Object.entries(filters).forEach(([key, value]) => {
    if (value) params.set(key, value)
  })
  return fetchJson<ApiResponse<ProjectPage>>(`${baseUrl}/api/v1/projects?${params.toString()}`, {
    headers: authHeaders(token, tenantId),
  })
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
