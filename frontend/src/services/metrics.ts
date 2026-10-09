import { authHeaders } from './auth'
import { fetchJson } from './http'

export type ApiResponse<T> = { code: number; message: string; data: T; trace_id: string }

export type PilotIndicator = {
  metric_code: string
  label: string
  value: number | null
  threshold: number
  pass: boolean
}

export type PilotAmountMetric = {
  metric_code: string
  label: string
  value: number | null
}

export type PilotAcceptance = {
  as_of: string
  indicators: PilotIndicator[]
  amounts: PilotAmountMetric[]
  last_snapshot_date: string | null
}

export type MetricSnapshotPoint = {
  snapshot_date: string
  metric_value: number | null
}

export function loadPilotAcceptance(baseUrl: string, token: string, tenantId: number) {
  return fetchJson<ApiResponse<PilotAcceptance>>(`${baseUrl}/api/v1/metrics/pilot-acceptance`, {
    headers: authHeaders(token, tenantId),
  })
}

export function collectMetricSnapshot(
  baseUrl: string,
  token: string,
  tenantId: number,
  date?: string,
) {
  return fetchJson<ApiResponse<{ snapshot_date: string; collected: number }>>(
    `${baseUrl}/api/v1/metrics/snapshots/collect`,
    {
      method: 'POST',
      headers: { ...authHeaders(token, tenantId), 'Content-Type': 'application/json' },
      body: JSON.stringify(date ? { date } : {}),
    },
  )
}

export function loadMetricSeries(
  baseUrl: string,
  token: string,
  tenantId: number,
  metricCode: string,
  from?: string,
  to?: string,
) {
  const params = new URLSearchParams({ metric_code: metricCode })
  if (from) params.set('from', from)
  if (to) params.set('to', to)
  return fetchJson<ApiResponse<MetricSnapshotPoint[]>>(
    `${baseUrl}/api/v1/metrics/snapshots?${params.toString()}`,
    { headers: authHeaders(token, tenantId) },
  )
}
