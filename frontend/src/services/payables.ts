import { authHeaders } from './auth'
import { fetchJson, fetchMultipart } from './http'
import type { GenericImportPreview } from './imports'

export type ApiResponse<T> = { code: number; message: string; data: T; trace_id: string }

export type PayablePlan = {
  id: number
  contract_id: number | null
  contract_no: string | null
  supplier_name: string
  node_name: string
  due_date: string
  plan_amount: string
  paid_amount: string
  status: string
  remark: string | null
  created_at: string
}

export type PaginatedPayables = {
  items: PayablePlan[]
  page: number
  page_size: number
  total: number
}

export type PayableImportPayload = {
  contract_no: string | null
  supplier_name: string | null
  node_name: string | null
  due_date: string | null
  plan_amount: string | null
  paid_amount: string | null
  status: string | null
  remark: string | null
}

export function loadPayables(
  baseUrl: string,
  token: string,
  tenantId: number,
  page = 1,
  pageSize = 20,
  status?: string,
) {
  const params = new URLSearchParams({ page: String(page), page_size: String(pageSize) })
  if (status) params.set('status', status)
  return fetchJson<ApiResponse<PaginatedPayables>>(
    `${baseUrl}/api/v1/payables?${params.toString()}`,
    { headers: authHeaders(token, tenantId) },
  )
}

export function previewPayables(baseUrl: string, token: string, tenantId: number, file: File) {
  const formData = new FormData()
  formData.append('file', file)
  return fetchMultipart<ApiResponse<GenericImportPreview<PayableImportPayload>>>(
    `${baseUrl}/api/v1/imports/payables/preview`,
    formData,
    token,
    tenantId,
  )
}

export function importPayables(baseUrl: string, token: string, tenantId: number, file: File) {
  const formData = new FormData()
  formData.append('file', file)
  return fetchMultipart<ApiResponse<Record<string, unknown>>>(
    `${baseUrl}/api/v1/imports/payables`,
    formData,
    token,
    tenantId,
  )
}
