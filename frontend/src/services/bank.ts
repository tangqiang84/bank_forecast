import { authHeaders } from './auth'
import { fetchJson, fetchMultipart } from './http'

export type ApiResponse<T> = { code: number; message: string; data: T; trace_id: string }

export type BankAccount = {
  id: number
  bank_name: string
  account_name: string
  account_no_last4: string
  currency: string
  status: string
  current_balance: string
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
  match_status: string
}

export type TransactionPage = { items: BankTransaction[]; page: number; page_size: number; total: number }

export function loadAccounts(baseUrl: string, token: string, tenantId: number) {
  return fetchJson<ApiResponse<BankAccount[]>>(`${baseUrl}/api/v1/bank-accounts`, {
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
