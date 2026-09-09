import { fetchJson } from './http'

export type User = {
  id: number
  tenant_id: number
  login_name: string
  display_name: string
  roles: string[]
  permissions: string[]
}

type AuthData = {
  access_token: string
  user: User
}

export type ApiResponse<T> = {
  code: number
  message: string
  data: T
  trace_id: string
}

const TOKEN_KEY = 'bank-connector-access-token'
const USER_KEY = 'bank-connector-user'

export async function login(baseUrl: string, loginName: string, password: string): Promise<User> {
  const response = await fetchJson<ApiResponse<AuthData>>(`${baseUrl}/api/v1/auth/login`, {
    method: 'POST',
    body: JSON.stringify({ login_name: loginName, password }),
  })
  localStorage.setItem(TOKEN_KEY, response.data.access_token)
  localStorage.setItem(USER_KEY, JSON.stringify(response.data.user))
  return response.data.user
}

export async function loadCurrentUser(baseUrl: string, token: string): Promise<User> {
  const cachedUser = localStorage.getItem(USER_KEY)
  const cached = cachedUser ? JSON.parse(cachedUser) as User : null
  if (!cached) {
    throw new Error('登录信息缺失')
  }
  await fetchJson<ApiResponse<User>>(`${baseUrl}/api/v1/auth/me`, {
    headers: authHeaders(token, cached.tenant_id),
  })
  return cached
}

export function getToken(): string {
  return localStorage.getItem(TOKEN_KEY) ?? ''
}

export function logout(): void {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
}

export function authHeaders(token: string, tenantId: number): Record<string, string> {
  return {
    Authorization: `Bearer ${token}`,
    'X-Tenant-Id': String(tenantId),
  }
}
