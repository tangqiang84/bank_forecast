import { fetchJson } from './http'
import { authHeaders } from './auth'

export type ApiResponse<T> = {
  code: number
  message: string
  data: T
  trace_id: string
}

export type DashboardOverview = {
  total_balance: string
  yesterday_net_inflow: string
  pending_exceptions: number
  idle_accounts: number
  match_rate: number
  last_sync_at: string
  top_receivables: Array<{
    contract_name: string
    customer_name: string
    amount: string
    due_date: string
    status: string
  }>
  recent_import_jobs: Array<{
    name: string
    status: string
    total_rows: number
    success_rows: number
    failed_rows: number
    message: string
  }>
  key_risks: Array<{
    title: string
    count: number
    description: string
  }>
}

export async function loadDashboardOverview(
  baseUrl: string,
  token: string,
  tenantId: number,
): Promise<ApiResponse<DashboardOverview>> {
  return fetchJson<ApiResponse<DashboardOverview>>(`${baseUrl}/api/v1/dashboard/overview`, {
    headers: authHeaders(token, tenantId),
  })
}
