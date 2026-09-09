import { authHeaders } from './auth'
import { fetchJson, fetchMultipart } from './http'

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
  closed_at?: string | null
}
export type MatchResult = {
  id: number
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

export function importContracts(baseUrl: string, token: string, tenantId: number, file: File) {
  const formData = new FormData()
  formData.append('file', file)
  return fetchMultipart<ApiResponse<Record<string, unknown>>>(`${baseUrl}/api/v1/imports/contracts`, formData, token, tenantId)
}

export function loadContracts(baseUrl: string, token: string, tenantId: number) {
  return fetchJson<ApiResponse<{ items: Contract[]; total: number }>>(`${baseUrl}/api/v1/contracts`, { headers: authHeaders(token, tenantId) })
}

export function loadReceivables(baseUrl: string, token: string, tenantId: number) {
  return fetchJson<ApiResponse<Receivable[]>>(`${baseUrl}/api/v1/contracts/receivables`, { headers: authHeaders(token, tenantId) })
}

export function runMatching(baseUrl: string, token: string, tenantId: number) {
  return fetchJson<ApiResponse<Record<string, number | string>>>(`${baseUrl}/api/v1/matching/receivables/run`, {
    method: 'POST',
    headers: authHeaders(token, tenantId),
  })
}

export function loadExceptions(baseUrl: string, token: string, tenantId: number) {
  return fetchJson<ApiResponse<ExceptionCase[]>>(`${baseUrl}/api/v1/matching/exceptions`, { headers: authHeaders(token, tenantId) })
}

export function loadMatchResults(baseUrl: string, token: string, tenantId: number) {
  return fetchJson<ApiResponse<{ items: MatchResult[]; total: number }>>(`${baseUrl}/api/v1/matching/results`, { headers: authHeaders(token, tenantId) })
}

export function confirmMatchResult(baseUrl: string, token: string, tenantId: number, resultId: number) {
  return fetchJson<ApiResponse<MatchResult>>(`${baseUrl}/api/v1/matching/results/${resultId}/confirm`, {
    method: 'POST',
    headers: authHeaders(token, tenantId),
  })
}

export function rejectMatchResult(baseUrl: string, token: string, tenantId: number, resultId: number, reason: string) {
  return fetchJson<ApiResponse<MatchResult>>(`${baseUrl}/api/v1/matching/results/${resultId}/reject`, {
    method: 'POST',
    headers: authHeaders(token, tenantId),
    body: JSON.stringify({ reason }),
  })
}

export function assignException(baseUrl: string, token: string, tenantId: number, exceptionId: number) {
  return fetchJson<ApiResponse<ExceptionCase>>(`${baseUrl}/api/v1/matching/exceptions/${exceptionId}/assign`, {
    method: 'POST',
    headers: authHeaders(token, tenantId),
  })
}

export function commentException(baseUrl: string, token: string, tenantId: number, exceptionId: number, text: string) {
  return fetchJson<ApiResponse<ExceptionCase>>(`${baseUrl}/api/v1/matching/exceptions/${exceptionId}/comment`, {
    method: 'POST',
    headers: authHeaders(token, tenantId),
    body: JSON.stringify({ text }),
  })
}

export function resolveException(baseUrl: string, token: string, tenantId: number, exceptionId: number, text: string) {
  return fetchJson<ApiResponse<ExceptionCase>>(`${baseUrl}/api/v1/matching/exceptions/${exceptionId}/resolve`, {
    method: 'POST',
    headers: authHeaders(token, tenantId),
    body: JSON.stringify({ text }),
  })
}

export function closeException(baseUrl: string, token: string, tenantId: number, exceptionId: number, text: string) {
  return fetchJson<ApiResponse<ExceptionCase>>(`${baseUrl}/api/v1/matching/exceptions/${exceptionId}/close`, {
    method: 'POST',
    headers: authHeaders(token, tenantId),
    body: JSON.stringify({ text }),
  })
}
