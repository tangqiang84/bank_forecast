import { authHeaders } from './auth'
import { fetchJson, fetchMultipart } from './http'

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
}

export type TransactionPage = { items: BankTransaction[]; page: number; page_size: number; total: number }

export function loadAccounts(baseUrl: string, token: string, tenantId: number) {
  return fetchJson<ApiResponse<BankAccount[]>>(`${baseUrl}/api/v1/bank-accounts`, {
    headers: authHeaders(token, tenantId),
  })
}

export type BankAccountInput = {
  bankCode: string
  bankName: string
  accountName: string
  accountNo: string
  currency: string
  currentBalance: string
}

export function createBankAccount(baseUrl: string, token: string, tenantId: number, input: BankAccountInput) {
  return fetchJson<ApiResponse<BankAccount>>(`${baseUrl}/api/v1/bank-accounts`, {
    method: 'POST',
    headers: { ...authHeaders(token, tenantId), 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  })
}

export function updateBankAccount(baseUrl: string, token: string, tenantId: number, id: number, input: BankAccountInput) {
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
  return fetchJson<ApiResponse<{ updated_accounts: number; accounts: BankAccount[] }>>(`${baseUrl}/api/v1/bank-accounts/idle-scan`, {
    method: 'POST',
    headers: authHeaders(token, tenantId),
  })
}

export function loadTransactions(baseUrl: string, token: string, tenantId: number, page = 1, filters: Record<string, string> = {}) {
  const params = new URLSearchParams({ page: String(page), page_size: '20' })
  Object.entries(filters).forEach(([key, value]) => { if (value) params.set(key, value) })
  return fetchJson<ApiResponse<TransactionPage>>(`${baseUrl}/api/v1/bank-transactions?${params.toString()}`, {
    headers: authHeaders(token, tenantId),
  })
}

export function loadTransactionDetail(baseUrl: string, token: string, tenantId: number, id: number) {
  return fetchJson<ApiResponse<Record<string, unknown>>>(`${baseUrl}/api/v1/bank-transactions/${id}`, { headers: authHeaders(token, tenantId) })
}

export function classifyTransaction(baseUrl: string, token: string, tenantId: number, id: number, category: string, purpose: string, remark: string) {
  return fetchJson<ApiResponse<BankTransaction>>(`${baseUrl}/api/v1/bank-transactions/${id}/manual-classify`, {
    method: 'POST', headers: { ...authHeaders(token, tenantId), 'Content-Type': 'application/json' }, body: JSON.stringify({ category, purpose, remark }),
  })
}

export function unlinkTransaction(baseUrl: string, token: string, tenantId: number, id: number, reason: string) {
  return fetchJson<ApiResponse<BankTransaction & { unlinked_groups: number; rolled_back_plans: number }>>(`${baseUrl}/api/v1/bank-transactions/${id}/unlink`, {
    method: 'POST', headers: { ...authHeaders(token, tenantId), 'Content-Type': 'application/json' }, body: JSON.stringify({ reason }),
  })
}

export async function exportTransactions(baseUrl: string, token: string, tenantId: number, filters: Record<string, string> = {}) {
  const params = new URLSearchParams()
  Object.entries(filters).forEach(([key, value]) => { if (value) params.set(key, value) })
  const response = await fetch(`${baseUrl}/api/v1/bank-transactions/export?${params.toString()}`, { headers: authHeaders(token, tenantId) })
  if (!response.ok) throw new Error(`HTTP ${response.status}`)
  return response.blob()
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
