import { authHeaders } from './auth'
import { fetchJson } from './http'
import type { ProjectRiskRule } from './projects'

export type ApiResponse<T> = { code: number; message: string; data: T; trace_id: string }

export type RuleVersion = {
  id: number
  rule_code: string
  threshold: string
  penalty: string
  max_penalty: string | null
  enabled: boolean
  version_no: number
  change_source: string
  remark: string | null
  updated_by: number | null
  created_at: string
}

export type RuleConfig = {
  match_scan_window_days: number
  match_exact_window_days: number
  match_suggest_window_days: number
  industry_template: string
}

export function loadRuleCenterRiskRules(baseUrl: string, token: string, tenantId: number) {
  return fetchJson<ApiResponse<ProjectRiskRule[]>>(`${baseUrl}/api/v1/rules/risk-rules`, {
    headers: authHeaders(token, tenantId),
  })
}

export function updateRuleCenterRiskRule(
  baseUrl: string,
  token: string,
  tenantId: number,
  ruleCode: string,
  payload: Record<string, unknown>,
) {
  return fetchJson<ApiResponse<ProjectRiskRule>>(
    `${baseUrl}/api/v1/rules/risk-rules/${encodeURIComponent(ruleCode)}`,
    {
      method: 'PUT',
      headers: { ...authHeaders(token, tenantId), 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    },
  )
}

export function loadRuleVersions(
  baseUrl: string,
  token: string,
  tenantId: number,
  ruleCode: string,
) {
  return fetchJson<ApiResponse<RuleVersion[]>>(
    `${baseUrl}/api/v1/rules/risk-rules/${encodeURIComponent(ruleCode)}/versions`,
    { headers: authHeaders(token, tenantId) },
  )
}

export function rollbackRuleVersion(
  baseUrl: string,
  token: string,
  tenantId: number,
  ruleCode: string,
  versionNo: number,
) {
  return fetchJson<ApiResponse<ProjectRiskRule>>(
    `${baseUrl}/api/v1/rules/risk-rules/${encodeURIComponent(ruleCode)}/rollback`,
    {
      method: 'POST',
      headers: { ...authHeaders(token, tenantId), 'Content-Type': 'application/json' },
      body: JSON.stringify({ version_no: versionNo }),
    },
  )
}

export function loadRuleConfig(baseUrl: string, token: string, tenantId: number) {
  return fetchJson<ApiResponse<RuleConfig>>(`${baseUrl}/api/v1/rules/config`, {
    headers: authHeaders(token, tenantId),
  })
}

export function updateRuleConfig(
  baseUrl: string,
  token: string,
  tenantId: number,
  payload: Record<string, unknown>,
) {
  return fetchJson<ApiResponse<RuleConfig>>(`${baseUrl}/api/v1/rules/config`, {
    method: 'PUT',
    headers: { ...authHeaders(token, tenantId), 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
}

export function applyIndustryTemplate(
  baseUrl: string,
  token: string,
  tenantId: number,
  industry: string,
) {
  return fetchJson<ApiResponse<RuleConfig & { applied: string }>>(
    `${baseUrl}/api/v1/rules/industry-template`,
    {
      method: 'POST',
      headers: { ...authHeaders(token, tenantId), 'Content-Type': 'application/json' },
      body: JSON.stringify({ industry }),
    },
  )
}
