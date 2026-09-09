import { authHeaders } from './auth'
import { fetchJson } from './http'

export type ApiResponse<T> = { code: number; message: string; data: T; trace_id: string }

export type ForecastJob = {
  id: number
  status: string
  model_name: string | null
  model_version: string | null
  horizon: number
  window_size: number
  input_start_date: string | null
  input_end_date: string | null
  attempt_count: number
  error_message: string | null
  started_at: string | null
  finished_at: string | null
  created_at: string
}

export type ForecastPoint = {
  id: number
  forecast_date: string
  forecast_amount: string
  expected_receivable: string
  projected_balance: string
  actual_amount: string | null
  deviation_amount: string | null
  risk_level: string
  risk_message: string
}

export type ForecastEvaluation = { evaluated_points: number; mae: string; rmse: string; mean_deviation: string }
export type ForecastDetail = { job: ForecastJob | null; results: ForecastPoint[]; evaluation: ForecastEvaluation }
export type ForecastModel = { id: number; version: string; model_name: string; status: string; config_json: string | null; metrics_json: string | null; activated_at: string | null; created_at: string }

export function loadLatestForecast(baseUrl: string, token: string, tenantId: number) {
  return fetchJson<ApiResponse<ForecastDetail>>(`${baseUrl}/api/v1/forecast/cashflow/latest`, {
    headers: authHeaders(token, tenantId),
  })
}

export function runForecast(baseUrl: string, token: string, tenantId: number, horizon: number, windowSize: number) {
  const params = new URLSearchParams({ horizon: String(horizon), window_size: String(windowSize) })
  return fetchJson<ApiResponse<ForecastDetail>>(`${baseUrl}/api/v1/forecast/cashflow/jobs?${params.toString()}`, {
    method: 'POST',
    headers: authHeaders(token, tenantId),
  })
}

export function retryForecast(baseUrl: string, token: string, tenantId: number, jobId: number) {
  return fetchJson<ApiResponse<ForecastDetail>>(`${baseUrl}/api/v1/forecast/cashflow/jobs/${jobId}/retry`, {
    method: 'POST',
    headers: authHeaders(token, tenantId),
  })
}

export function backfillForecastActuals(baseUrl: string, token: string, tenantId: number, jobId: number) {
  return fetchJson<ApiResponse<ForecastDetail>>(`${baseUrl}/api/v1/forecast/cashflow/jobs/${jobId}/actuals`, {
    method: 'POST',
    headers: authHeaders(token, tenantId),
  })
}

export function loadForecastModels(baseUrl: string, token: string, tenantId: number) {
  return fetchJson<ApiResponse<ForecastModel[]>>(`${baseUrl}/api/v1/forecast/models`, {
    headers: authHeaders(token, tenantId),
  })
}

export function activateForecastModel(baseUrl: string, token: string, tenantId: number, version: string) {
  return fetchJson<ApiResponse<ForecastModel>>(`${baseUrl}/api/v1/forecast/models/${encodeURIComponent(version)}/activate`, {
    method: 'POST',
    headers: authHeaders(token, tenantId),
  })
}
