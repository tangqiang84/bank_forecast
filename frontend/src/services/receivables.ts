import { authHeaders } from './auth'
import { fetchBlob, fetchJson, fetchMultipart } from './http'
import type { GenericImportPreview } from './imports'

export type ApiResponse<T> = { code: number; message: string; data: T; trace_id: string }
export type Contract = {
  id: number
  contract_no: string
  contract_name: string
  customer_name: string
  project_name: string | null
  contract_amount: string
  receivable_amount: string
  paid_amount: string
}
export type Receivable = {
  id: number
  contract_id: number
  contract_no: string
  contract_name: string
  customer_name: string
  node_name: string
  due_date: string
  plan_amount: string
  paid_amount: string
  status: string
}
export type ExceptionCase = {
  id: number
  exception_no: string
  exception_type: string
  title: string
  description: string
  status: string
  severity: string
  due_date: string | null
  owner_user_id: number | null
  owner_name?: string | null
  stage?: string
  closed_at?: string | null
}

export type ExceptionOwnerStat = {
  owner_user_id: number
  owner_name: string | null
  total: number
  active: number
  resolved_closed: number
  overdue: number
}

export type ExceptionStats = {
  active_count: number
  unassigned_count: number
  my_todo_count: number
  pending_close_count: number
  overdue_count: number
  closed_count: number
  false_positive_count: number
  total_count: number
  closure_rate: number
  avg_resolution_hours: number
  by_type: Array<{ exception_type: string; count: number }>
  by_owner: ExceptionOwnerStat[]
}
export type ExceptionAttachment = {
  id: number
  exception_case_id: number
  file_name: string
  content_type: string
  file_size: number
  uploaded_by: number | null
  created_at: string
}
export type MatchResult = {
  id: number
  match_group_id: string
  allocation_mode: string
  allocated_amount: string
  allocation_count: number
  allocation_total: string
  bank_transaction_id: number
  transaction_no: string
  amount: string
  transaction_match_status: string
  contract_id: number | null
  contract_receivable_plan_id: number | null
  contract_no: string | null
  contract_name: string | null
  node_name: string | null
  match_type: string
  confidence_level: string
  match_status: string
  match_reason: string
  confirmed_by: number | null
  confirmed_at: string | null
}
export type Paged<T> = { items: T[]; page: number; page_size: number; total: number }

export type ContractImportPayload = {
  contract_no: string | null
  contract_name: string | null
  customer_name: string | null
  project_no: string | null
  project_name: string | null
  contract_amount: string | null
  node_name: string | null
  node_type: string | null
  due_date: string | null
  plan_amount: string | null
  owner_name: string | null
}

export function previewContracts(baseUrl: string, token: string, tenantId: number, file: File) {
  const formData = new FormData()
  formData.append('file', file)
  return fetchMultipart<ApiResponse<GenericImportPreview<ContractImportPayload>>>(
    `${baseUrl}/api/v1/imports/contracts/preview`,
    formData,
    token,
    tenantId,
  )
}

export function importContracts(baseUrl: string, token: string, tenantId: number, file: File) {
  const formData = new FormData()
  formData.append('file', file)
  return fetchMultipart<ApiResponse<Record<string, unknown>>>(
    `${baseUrl}/api/v1/imports/contracts`,
    formData,
    token,
    tenantId,
  )
}

export function loadContracts(
  baseUrl: string,
  token: string,
  tenantId: number,
  filters: Record<string, string> = {},
) {
  const params = new URLSearchParams({ page: '1', page_size: '100' })
  Object.entries(filters).forEach(([key, value]) => {
    if (value) params.set(key, value)
  })
  return fetchJson<ApiResponse<{ items: Contract[]; total: number }>>(
    `${baseUrl}/api/v1/contracts?${params.toString()}`,
    { headers: authHeaders(token, tenantId) },
  )
}

export function loadReceivables(
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
  return fetchJson<ApiResponse<Paged<Receivable>>>(
    `${baseUrl}/api/v1/contracts/receivables?${params.toString()}`,
    { headers: authHeaders(token, tenantId) },
  )
}

export function runMatching(baseUrl: string, token: string, tenantId: number) {
  return fetchJson<ApiResponse<Record<string, number | string>>>(
    `${baseUrl}/api/v1/matching/receivables/run`,
    {
      method: 'POST',
      headers: authHeaders(token, tenantId),
    },
  )
}

export function loadExceptions(
  baseUrl: string,
  token: string,
  tenantId: number,
  page = 1,
  pageSize = 20,
  filters: Record<string, string> = {},
) {
  const params = new URLSearchParams({
    page: String(page),
    page_size: String(pageSize),
    active_only: 'true',
  })
  Object.entries(filters).forEach(([key, value]) => {
    if (value) params.set(key, value)
  })
  if (filters.queue) params.delete('active_only')
  return fetchJson<ApiResponse<Paged<ExceptionCase>>>(
    `${baseUrl}/api/v1/matching/exceptions?${params.toString()}`,
    { headers: authHeaders(token, tenantId) },
  )
}

export function loadExceptionStats(baseUrl: string, token: string, tenantId: number) {
  return fetchJson<ApiResponse<ExceptionStats>>(`${baseUrl}/api/v1/matching/exceptions/stats`, {
    headers: authHeaders(token, tenantId),
  })
}

export function loadMatchResults(
  baseUrl: string,
  token: string,
  tenantId: number,
  page = 1,
  pageSize = 20,
) {
  return fetchJson<ApiResponse<Paged<MatchResult>>>(
    `${baseUrl}/api/v1/matching/results?page=${page}&page_size=${pageSize}`,
    { headers: authHeaders(token, tenantId) },
  )
}

export function loadContractDetail(baseUrl: string, token: string, tenantId: number, id: number) {
  return fetchJson<ApiResponse<Record<string, unknown>>>(`${baseUrl}/api/v1/contracts/${id}`, {
    headers: authHeaders(token, tenantId),
  })
}

export function loadMatchResultDetail(
  baseUrl: string,
  token: string,
  tenantId: number,
  id: number,
) {
  return fetchJson<ApiResponse<Record<string, unknown>>>(
    `${baseUrl}/api/v1/matching/results/${id}`,
    { headers: authHeaders(token, tenantId) },
  )
}

export function loadExceptionDetail(baseUrl: string, token: string, tenantId: number, id: number) {
  return fetchJson<ApiResponse<Record<string, unknown>>>(
    `${baseUrl}/api/v1/matching/exceptions/${id}`,
    { headers: authHeaders(token, tenantId) },
  )
}

export function confirmMatchResult(
  baseUrl: string,
  token: string,
  tenantId: number,
  resultId: number,
) {
  return fetchJson<ApiResponse<MatchResult>>(
    `${baseUrl}/api/v1/matching/results/${resultId}/confirm`,
    {
      method: 'POST',
      headers: authHeaders(token, tenantId),
    },
  )
}

export function rejectMatchResult(
  baseUrl: string,
  token: string,
  tenantId: number,
  resultId: number,
  reason: string,
) {
  return fetchJson<ApiResponse<MatchResult>>(
    `${baseUrl}/api/v1/matching/results/${resultId}/reject`,
    {
      method: 'POST',
      headers: authHeaders(token, tenantId),
      body: JSON.stringify({ reason }),
    },
  )
}

export function assignException(
  baseUrl: string,
  token: string,
  tenantId: number,
  exceptionId: number,
) {
  return fetchJson<ApiResponse<ExceptionCase>>(
    `${baseUrl}/api/v1/matching/exceptions/${exceptionId}/assign`,
    {
      method: 'POST',
      headers: authHeaders(token, tenantId),
    },
  )
}

export function commentException(
  baseUrl: string,
  token: string,
  tenantId: number,
  exceptionId: number,
  text: string,
) {
  return fetchJson<ApiResponse<ExceptionCase>>(
    `${baseUrl}/api/v1/matching/exceptions/${exceptionId}/comment`,
    {
      method: 'POST',
      headers: authHeaders(token, tenantId),
      body: JSON.stringify({ text }),
    },
  )
}

export function resolveException(
  baseUrl: string,
  token: string,
  tenantId: number,
  exceptionId: number,
  text: string,
) {
  return fetchJson<ApiResponse<ExceptionCase>>(
    `${baseUrl}/api/v1/matching/exceptions/${exceptionId}/resolve`,
    {
      method: 'POST',
      headers: authHeaders(token, tenantId),
      body: JSON.stringify({ text }),
    },
  )
}

export function closeException(
  baseUrl: string,
  token: string,
  tenantId: number,
  exceptionId: number,
  text: string,
) {
  return fetchJson<ApiResponse<ExceptionCase>>(
    `${baseUrl}/api/v1/matching/exceptions/${exceptionId}/close`,
    {
      method: 'POST',
      headers: authHeaders(token, tenantId),
      body: JSON.stringify({ text }),
    },
  )
}

export function markFalsePositive(
  baseUrl: string,
  token: string,
  tenantId: number,
  exceptionId: number,
  text: string,
) {
  return fetchJson<ApiResponse<ExceptionCase>>(
    `${baseUrl}/api/v1/matching/exceptions/${exceptionId}/false-positive`,
    {
      method: 'POST',
      headers: { ...authHeaders(token, tenantId), 'Content-Type': 'application/json' },
      body: JSON.stringify({ text }),
    },
  )
}

export function uploadExceptionAttachment(
  baseUrl: string,
  token: string,
  tenantId: number,
  exceptionId: number,
  file: File,
) {
  const formData = new FormData()
  formData.append('file', file)
  return fetchMultipart<ApiResponse<ExceptionAttachment>>(
    `${baseUrl}/api/v1/matching/exceptions/${exceptionId}/attachments`,
    formData,
    token,
    tenantId,
  )
}
export function batchExceptionAction(
  baseUrl: string,
  token: string,
  tenantId: number,
  exceptionIds: number[],
  action: string,
  text = '',
) {
  return fetchJson<ApiResponse<{ updated: number; action: string }>>(
    `${baseUrl}/api/v1/matching/exceptions/batch-action`,
    {
      method: 'POST',
      headers: { ...authHeaders(token, tenantId), 'Content-Type': 'application/json' },
      body: JSON.stringify({ exception_ids: exceptionIds, action, text }),
    },
  )
}
export function deleteExceptionAttachment(
  baseUrl: string,
  token: string,
  tenantId: number,
  attachmentId: number,
) {
  return fetchJson<ApiResponse<{ deleted: boolean }>>(
    `${baseUrl}/api/v1/matching/exceptions/attachments/${attachmentId}`,
    { method: 'DELETE', headers: authHeaders(token, tenantId) },
  )
}
export function previewExceptionAttachment(
  baseUrl: string,
  token: string,
  tenantId: number,
  attachmentId: number,
) {
  return fetchBlob(`${baseUrl}/api/v1/matching/exceptions/attachments/${attachmentId}/preview`, {
    headers: authHeaders(token, tenantId),
  })
}
