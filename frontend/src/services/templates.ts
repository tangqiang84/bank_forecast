import { authHeaders } from './auth'
import { fetchJson } from './http'

export type ApiResponse<T> = { code: number; message: string; data: T; trace_id: string }

export type TemplateField = {
  field: string
  label: string
}

export type TemplateMapping = {
  id: number
  header: string
  field: string
  created_at: string
  updated_at: string
}

export type BankTemplateFieldMapping = {
  header: string
  field: string
}

export type BankTemplate = {
  bank_code: string
  bank_name: string
  format: string
  recognition: string
  field_mappings: BankTemplateFieldMapping[]
}

export type TemplateOverview = {
  templates: BankTemplate[]
  fields: TemplateField[]
  mappings: TemplateMapping[]
}

export function loadTemplateOverview(baseUrl: string, token: string, tenantId: number) {
  return fetchJson<ApiResponse<TemplateOverview>>(`${baseUrl}/api/v1/bank-templates`, {
    headers: authHeaders(token, tenantId),
  })
}

export function createTemplateMapping(
  baseUrl: string,
  token: string,
  tenantId: number,
  payload: { header: string; field: string },
) {
  return fetchJson<ApiResponse<TemplateMapping>>(`${baseUrl}/api/v1/bank-templates/mappings`, {
    method: 'POST',
    headers: { ...authHeaders(token, tenantId), 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
}

export function updateTemplateMapping(
  baseUrl: string,
  token: string,
  tenantId: number,
  id: number,
  payload: { header: string; field: string },
) {
  return fetchJson<ApiResponse<TemplateMapping>>(
    `${baseUrl}/api/v1/bank-templates/mappings/${id}`,
    {
      method: 'PUT',
      headers: { ...authHeaders(token, tenantId), 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    },
  )
}

export function deleteTemplateMapping(
  baseUrl: string,
  token: string,
  tenantId: number,
  id: number,
) {
  return fetchJson<ApiResponse<{ deleted: boolean }>>(
    `${baseUrl}/api/v1/bank-templates/mappings/${id}`,
    { method: 'DELETE', headers: authHeaders(token, tenantId) },
  )
}
