import { authHeaders } from './auth'
import { fetchBlob, fetchJson, fetchMultipart } from './http'

export type ApiResponse<T> = { code: number; message: string; data: T; trace_id: string }

export type BankAccount = {
  bank_code: string
  id: number
  bank_name: string
  account_name: string
  account_no_last4: string
  currency: string
  status: string
  current_balance: string
  last_transaction_at: string | null
  idle_days: number | null
  idle_level: 'normal' | 'idle_30' | 'idle_90' | 'idle_180' | null
}

export type BankTransaction = {
  id: number
  bank_account_id: number
  transaction_no: string
  transaction_date: string
  direction: string
  amount: string
  balance_after: string | null
  counterparty_name: string | null
  summary: string | null
  purpose: string | null
  category: string | null
  match_status: string
  bank_name?: string | null
  account_no_last4?: string | null
}

export type TransactionPage = {
  items: BankTransaction[]
  page: number
  page_size: number
  total: number
}
export type BankAccountPage = {
  items: BankAccount[]
  page: number
  page_size: number
  total: number
}

export async function loadAccounts(
  baseUrl: string,
  token: string,
  tenantId: number,
  page = 1,
  pageSize = 20,
) {
  const response = await fetchJson<ApiResponse<BankAccountPage | BankAccount[]>>(
    `${baseUrl}/api/v1/bank-accounts?page=${page}&page_size=${pageSize}`,
    {
      headers: authHeaders(token, tenantId),
    },
  )
  if (Array.isArray(response.data)) {
    return {
      ...response,
      data: { items: response.data, page, page_size: pageSize, total: response.data.length },
    } as ApiResponse<BankAccountPage>
  }
  return response as ApiResponse<BankAccountPage>
}

export function loadAccountDetail(baseUrl: string, token: string, tenantId: number, id: number) {
  return fetchJson<ApiResponse<BankAccount & Record<string, unknown>>>(
    `${baseUrl}/api/v1/bank-accounts/${id}`,
    { headers: authHeaders(token, tenantId) },
  )
}

export type BankAccountInput = {
  bankCode: string
  bankName: string
  accountName: string
  accountNo: string
  currency: string
  currentBalance: string
}

export function createBankAccount(
  baseUrl: string,
  token: string,
  tenantId: number,
  input: BankAccountInput,
) {
  return fetchJson<ApiResponse<BankAccount>>(`${baseUrl}/api/v1/bank-accounts`, {
    method: 'POST',
    headers: { ...authHeaders(token, tenantId), 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  })
}

export function updateBankAccount(
  baseUrl: string,
  token: string,
  tenantId: number,
  id: number,
  input: BankAccountInput,
) {
  return fetchJson<ApiResponse<BankAccount>>(`${baseUrl}/api/v1/bank-accounts/${id}`, {
    method: 'PUT',
    headers: { ...authHeaders(token, tenantId), 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  })
}

export function closeBankAccount(baseUrl: string, token: string, tenantId: number, id: number) {
  return fetchJson<ApiResponse<BankAccount>>(`${baseUrl}/api/v1/bank-accounts/${id}/close`, {
    method: 'POST',
    headers: authHeaders(token, tenantId),
  })
}

export function scanIdleAccounts(baseUrl: string, token: string, tenantId: number) {
  return fetchJson<ApiResponse<{ updated_accounts: number; accounts: BankAccount[] }>>(
    `${baseUrl}/api/v1/bank-accounts/idle-scan`,
    {
      method: 'POST',
      headers: authHeaders(token, tenantId),
    },
  )
}

export function loadTransactions(
  baseUrl: string,
  token: string,
  tenantId: number,
  page = 1,
  pageSize = 20,
  filters: Record<string, string> = {},
) {
  const params = new URLSearchParams({ page: String(page), page_size: String(pageSize) })
  Object.entries(filters).forEach(([key, value]) => {
    if (value) params.set(key, value)
  })
  return fetchJson<ApiResponse<TransactionPage>>(
    `${baseUrl}/api/v1/bank-transactions?${params.toString()}`,
    {
      headers: authHeaders(token, tenantId),
    },
  )
}

export function loadTransactionDetail(
  baseUrl: string,
  token: string,
  tenantId: number,
  id: number,
) {
  return fetchJson<ApiResponse<Record<string, unknown>>>(
    `${baseUrl}/api/v1/bank-transactions/${id}`,
    { headers: authHeaders(token, tenantId) },
  )
}

export function classifyTransaction(
  baseUrl: string,
  token: string,
  tenantId: number,
  id: number,
  category: string,
  purpose: string,
  remark: string,
) {
  return fetchJson<ApiResponse<BankTransaction>>(
    `${baseUrl}/api/v1/bank-transactions/${id}/manual-classify`,
    {
      method: 'POST',
      headers: { ...authHeaders(token, tenantId), 'Content-Type': 'application/json' },
      body: JSON.stringify({ category, purpose, remark }),
    },
  )
}

export function unlinkTransaction(
  baseUrl: string,
  token: string,
  tenantId: number,
  id: number,
  reason: string,
) {
  return fetchJson<
    ApiResponse<BankTransaction & { unlinked_groups: number; rolled_back_plans: number }>
  >(`${baseUrl}/api/v1/bank-transactions/${id}/unlink`, {
    method: 'POST',
    headers: { ...authHeaders(token, tenantId), 'Content-Type': 'application/json' },
    body: JSON.stringify({ reason }),
  })
}

export function batchClassifyTransactions(
  baseUrl: string,
  token: string,
  tenantId: number,
  transactionIds: number[],
  category: string,
  purpose: string,
  remark: string,
) {
  return fetchJson<ApiResponse<{ updated: number }>>(
    `${baseUrl}/api/v1/bank-transactions/batch-classify`,
    {
      method: 'POST',
      headers: { ...authHeaders(token, tenantId), 'Content-Type': 'application/json' },
      body: JSON.stringify({ transaction_ids: transactionIds, category, purpose, remark }),
    },
  )
}

export function batchUnlinkTransactions(
  baseUrl: string,
  token: string,
  tenantId: number,
  transactionIds: number[],
  reason: string,
) {
  return fetchJson<
    ApiResponse<{ processed: number; unlinked_groups: number; rolled_back_plans: number }>
  >(`${baseUrl}/api/v1/bank-transactions/batch-unlink`, {
    method: 'POST',
    headers: { ...authHeaders(token, tenantId), 'Content-Type': 'application/json' },
    body: JSON.stringify({ transaction_ids: transactionIds, reason }),
  })
}

export async function exportTransactions(
  baseUrl: string,
  token: string,
  tenantId: number,
  filters: Record<string, string> = {},
) {
  const params = new URLSearchParams()
  Object.entries(filters).forEach(([key, value]) => {
    if (value) params.set(key, value)
  })
  return fetchBlob(`${baseUrl}/api/v1/bank-transactions/export?${params.toString()}`, {
    headers: authHeaders(token, tenantId),
  })
}

export function importStatements(
  baseUrl: string,
  token: string,
  tenantId: number,
  accountId: number,
  file: File,
) {
  const formData = new FormData()
  formData.append('bank_account_id', String(accountId))
  formData.append('file', file)
  return fetchMultipart<ApiResponse<Record<string, unknown>>>(
    `${baseUrl}/api/v1/imports/bank-statements`,
    formData,
    token,
    tenantId,
  )
}

export type ImportPreviewRow = {
  id: number
  row_no: number
  transaction_no: string | null
  transaction_date: string | null
  direction: string | null
  amount: string | null
  balance_after: string | null
  counterparty_name: string | null
  summary: string | null
  status: string
  error_message: string | null
}

export type ImportPreview = Record<string, unknown> & {
  job_id: number
  status: string
  total_rows: number
  success_rows: number
  failed_rows: number
  skipped_rows: number
  preview_rows: ImportPreviewRow[]
  recognized_templates?: Array<{
    sheet_name: string
    bank_name?: string
    status: string
    message: string
  }>
}

export function previewStatements(
  baseUrl: string,
  token: string,
  tenantId: number,
  accountId: number,
  file: File,
) {
  const formData = new FormData()
  formData.append('bank_account_id', String(accountId))
  formData.append('file', file)
  return fetchMultipart<ApiResponse<ImportPreview>>(
    `${baseUrl}/api/v1/imports/bank-statements/preview`,
    formData,
    token,
    tenantId,
  )
}

export function loadImportPreview(baseUrl: string, token: string, tenantId: number, jobId: number) {
  return fetchJson<ApiResponse<ImportPreview>>(`${baseUrl}/api/v1/imports/${jobId}/preview`, {
    headers: authHeaders(token, tenantId),
  })
}

export function confirmImportPreview(
  baseUrl: string,
  token: string,
  tenantId: number,
  jobId: number,
) {
  return fetchJson<ApiResponse<ImportPreview>>(`${baseUrl}/api/v1/imports/${jobId}/confirm`, {
    method: 'POST',
    headers: authHeaders(token, tenantId),
  })
}

export function retryImportErrors(
  baseUrl: string,
  token: string,
  tenantId: number,
  jobId: number,
  rows: Array<Record<string, unknown>>,
) {
  return fetchJson<ApiResponse<ImportPreview>>(`${baseUrl}/api/v1/imports/${jobId}/retry-errors`, {
    method: 'POST',
    headers: { ...authHeaders(token, tenantId), 'Content-Type': 'application/json' },
    body: JSON.stringify({ rows }),
  })
}

export type BankConnection = {
  id: number
  bank_code: string
  bank_name: string
  connection_type: string
  bank_account_id: number | null
  account_name?: string | null
  account_no_last4?: string | null
  status: string
  last_tested_at: string | null
  last_test_status: string | null
  last_test_message: string | null
  remark: string | null
  created_at: string
}

export type BankTemplate = {
  bank_code: string
  bank_name: string
  format: string
  recognition: string
  field_mappings: Array<{ header: string; field: string }>
}

export type ConnectionTestResult = {
  connection_id: number
  bank_code: string
  status: string
  message: string
  duration_ms: number
  parsed_rows?: number
  error_rows?: number
  recognized_templates?: Array<{
    sheet_name: string
    bank_name?: string
    status: string
    message: string
  }>
}

export function loadBankConnections(baseUrl: string, token: string, tenantId: number) {
  return fetchJson<ApiResponse<BankConnection[]>>(`${baseUrl}/api/v1/bank-connections`, {
    headers: authHeaders(token, tenantId),
  })
}

export function loadBankTemplates(baseUrl: string, token: string, tenantId: number) {
  return fetchJson<ApiResponse<BankTemplate[]>>(`${baseUrl}/api/v1/bank-connections/templates`, {
    headers: authHeaders(token, tenantId),
  })
}

export function createBankConnection(
  baseUrl: string,
  token: string,
  tenantId: number,
  payload: Record<string, unknown>,
) {
  return fetchJson<ApiResponse<BankConnection>>(`${baseUrl}/api/v1/bank-connections`, {
    method: 'POST',
    headers: { ...authHeaders(token, tenantId), 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
}

export function deleteBankConnection(baseUrl: string, token: string, tenantId: number, id: number) {
  return fetchJson<ApiResponse<{ deleted: boolean }>>(`${baseUrl}/api/v1/bank-connections/${id}`, {
    method: 'DELETE',
    headers: authHeaders(token, tenantId),
  })
}

export function testBankConnection(
  baseUrl: string,
  token: string,
  tenantId: number,
  id: number,
  file: File,
) {
  const formData = new FormData()
  formData.append('file', file)
  return fetchMultipart<ApiResponse<ConnectionTestResult>>(
    `${baseUrl}/api/v1/bank-connections/${id}/test`,
    formData,
    token,
    tenantId,
  )
}
