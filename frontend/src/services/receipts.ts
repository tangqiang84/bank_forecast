import { authHeaders } from './auth'
import { fetchBlob, fetchJson, fetchMultipart } from './http'
import type { GenericImportPreview } from './imports'

export type ApiResponse<T> = { code: number; message: string; data: T; trace_id: string }

export type Receipt = {
  id: number
  bank_transaction_id: number | null
  bank_name: string | null
  receipt_no: string
  print_date: string | null
  transaction_date: string
  transaction_time: string | null
  currency: string
  payer_name: string | null
  payer_account_last4: string | null
  payee_name: string | null
  payee_account_last4: string | null
  payer_bank: string | null
  payee_bank: string | null
  amount: string
  summary: string | null
  transaction_no: string | null
  channel: string | null
  verification_code: string | null
  image_file_name: string | null
  image_content_type: string | null
  image_size: number | null
  has_image: boolean
  created_at: string
}

export type ReceiptPage = { items: Receipt[]; page: number; page_size: number; total: number }

export type ReceiptImportPayload = {
  bank_name: string | null
  receipt_no: string | null
  print_date: string | null
  transaction_date: string | null
  transaction_time: string | null
  currency: string | null
  payer_name: string | null
  payer_account_last4: string | null
  payee_name: string | null
  payee_account_last4: string | null
  payer_bank: string | null
  payee_bank: string | null
  amount: string | null
  summary: string | null
  transaction_no: string | null
  channel: string | null
  verification_code: string | null
}

export function loadReceipts(
  baseUrl: string,
  token: string,
  tenantId: number,
  page = 1,
  pageSize = 20,
  transactionNo?: string,
) {
  const suffix = transactionNo ? `&transaction_no=${encodeURIComponent(transactionNo)}` : ''
  return fetchJson<ApiResponse<ReceiptPage>>(
    `${baseUrl}/api/v1/receipts?page=${page}&page_size=${pageSize}${suffix}`,
    { headers: authHeaders(token, tenantId) },
  )
}

export function previewReceipts(baseUrl: string, token: string, tenantId: number, file: File) {
  const formData = new FormData()
  formData.append('file', file)
  return fetchMultipart<ApiResponse<GenericImportPreview<ReceiptImportPayload>>>(
    `${baseUrl}/api/v1/imports/receipts/preview`,
    formData,
    token,
    tenantId,
  )
}

export function importReceipts(baseUrl: string, token: string, tenantId: number, file: File) {
  const formData = new FormData()
  formData.append('file', file)
  return fetchMultipart<ApiResponse<Record<string, unknown>>>(
    `${baseUrl}/api/v1/imports/receipts`,
    formData,
    token,
    tenantId,
  )
}

export function uploadReceiptImage(
  baseUrl: string,
  token: string,
  tenantId: number,
  receiptId: number,
  file: File,
) {
  const formData = new FormData()
  formData.append('file', file)
  return fetchMultipart<ApiResponse<Receipt>>(
    `${baseUrl}/api/v1/receipts/${receiptId}/image`,
    formData,
    token,
    tenantId,
  )
}

export function previewReceiptImage(
  baseUrl: string,
  token: string,
  tenantId: number,
  receiptId: number,
) {
  return fetchBlob(`${baseUrl}/api/v1/receipts/${receiptId}/image/preview`, {
    headers: authHeaders(token, tenantId),
  })
}

export function downloadReceiptImage(
  baseUrl: string,
  token: string,
  tenantId: number,
  receiptId: number,
) {
  return fetchBlob(`${baseUrl}/api/v1/receipts/${receiptId}/image/download`, {
    headers: authHeaders(token, tenantId),
  })
}
