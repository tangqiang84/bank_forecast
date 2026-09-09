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
