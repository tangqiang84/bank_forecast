import { authHeaders } from './auth'
import { fetchJson } from './http'

export type ApiResponse<T> = { code: number; message: string; data: T; trace_id: string }

export type SystemUser = {
  id: number
  login_name: string
  display_name: string
  status: string
  last_login_at: string | null
  created_at: string
  roles?: string[]
  scoped_project_count?: number
}

export type SystemUserPage = { items: SystemUser[]; page: number; page_size: number; total: number }

export type SystemRole = {
  id: number
  role_code: string
  role_name: string
  status: string
  created_at: string
  permission_codes?: string[]
}

export type PermissionPoint = { permission_code: string; permission_name: string; module: string }

function jsonHeaders(token: string, tenantId: number) {
  return { ...authHeaders(token, tenantId), 'Content-Type': 'application/json' }
}

export function loadSystemUsers(
  baseUrl: string,
  token: string,
  tenantId: number,
  page = 1,
  pageSize = 50,
  keyword = '',
) {
  const params = new URLSearchParams({ page: String(page), page_size: String(pageSize) })
  if (keyword) params.set('keyword', keyword)
  return fetchJson<ApiResponse<SystemUserPage>>(`${baseUrl}/api/v1/system/users?${params}`, {
    headers: authHeaders(token, tenantId),
  })
}

export function createSystemUser(
  baseUrl: string,
  token: string,
  tenantId: number,
  payload: { login_name: string; display_name: string; password: string; role_codes: string[] },
) {
  return fetchJson<ApiResponse<SystemUser>>(`${baseUrl}/api/v1/system/users`, {
    method: 'POST',
    headers: jsonHeaders(token, tenantId),
    body: JSON.stringify(payload),
  })
}

export function updateSystemUser(
  baseUrl: string,
  token: string,
  tenantId: number,
  id: number,
  payload: Record<string, unknown>,
) {
  return fetchJson<ApiResponse<SystemUser>>(`${baseUrl}/api/v1/system/users/${id}`, {
    method: 'PUT',
    headers: jsonHeaders(token, tenantId),
    body: JSON.stringify(payload),
  })
}

export function resetUserPassword(
  baseUrl: string,
  token: string,
  tenantId: number,
  id: number,
  password: string,
) {
  return fetchJson<ApiResponse<{ reset: boolean }>>(
    `${baseUrl}/api/v1/system/users/${id}/reset-password`,
    { method: 'POST', headers: jsonHeaders(token, tenantId), body: JSON.stringify({ password }) },
  )
}

export function loadUserProjectScope(baseUrl: string, token: string, tenantId: number, id: number) {
  return fetchJson<ApiResponse<number[]>>(`${baseUrl}/api/v1/system/users/${id}/project-scope`, {
    headers: authHeaders(token, tenantId),
  })
}

export function updateUserProjectScope(
  baseUrl: string,
  token: string,
  tenantId: number,
  id: number,
  projectIds: number[],
) {
  return fetchJson<ApiResponse<{ user_id: number; project_ids: number[] }>>(
    `${baseUrl}/api/v1/system/users/${id}/project-scope`,
    {
      method: 'PUT',
      headers: jsonHeaders(token, tenantId),
      body: JSON.stringify({ project_ids: projectIds }),
    },
  )
}

export function loadSystemRoles(baseUrl: string, token: string, tenantId: number) {
  return fetchJson<ApiResponse<SystemRole[]>>(`${baseUrl}/api/v1/system/roles`, {
    headers: authHeaders(token, tenantId),
  })
}

export function updateRolePermissions(
  baseUrl: string,
  token: string,
  tenantId: number,
  roleId: number,
  permissionCodes: string[],
) {
  return fetchJson<ApiResponse<SystemRole>>(
    `${baseUrl}/api/v1/system/roles/${roleId}/permissions`,
    {
      method: 'PUT',
      headers: jsonHeaders(token, tenantId),
      body: JSON.stringify({ permission_codes: permissionCodes }),
    },
  )
}

export function loadPermissionPoints(baseUrl: string, token: string, tenantId: number) {
  return fetchJson<ApiResponse<PermissionPoint[]>>(`${baseUrl}/api/v1/system/permissions`, {
    headers: authHeaders(token, tenantId),
  })
}
