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
  receivable_amount?: string
  paid_receivable_amount?: string
  overdue_receivable_amount?: string
  exception_count?: number
  last_sync_at: string
  payable_top?: Array<{
    id: number
    contract_id: number | null
    contract_no: string | null
    supplier_name: string
    node_name: string
    due_date: string
    amount: string
    status: string
  }>
  payable_7d_amount?: string
  payable_30d_amount?: string
  receivable_30d_amount?: string
  funding_gap?: {
    horizon_days: number
    balance: string
    receivable_30d: string
    payable_30d: string
    gap_amount: string
    has_gap: boolean
  }
  risk_projects?: Array<{
    project_no: string
    project_name: string
    customer_name: string
    risk_score: number
    risk_level: string
    risk_items: string[]
  }>
  top_receivables: Array<{
    contract_name: string
    customer_name: string
    amount: string
    due_date: string
    status: string
  }>
  recent_import_jobs: Array<{
    job_id?: number
    name: string
    status: string
    total_rows: number
    success_rows: number
    failed_rows: number
    skipped_rows: number
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

export type DashboardTrendPoint = {
  date: string
  income: number
  expense: number
  net_inflow: number
  balance: number
  open_exceptions: number
  match_rate: number | null
  receivable_total: number | null
  received_total: number | null
  overdue_receivable: number | null
}

export type DashboardTrends = {
  days: number
  start: string
  end: string
  series: DashboardTrendPoint[]
}

export function loadDashboardTrends(baseUrl: string, token: string, tenantId: number, days = 30) {
  return fetchJson<ApiResponse<DashboardTrends>>(
    `${baseUrl}/api/v1/dashboard/trends?days=${days}`,
    { headers: authHeaders(token, tenantId) },
  )
}
