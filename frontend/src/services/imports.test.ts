import { afterEach, describe, expect, it, vi } from 'vitest'

import { previewFinanceRecords } from './finance'
import { confirmImportJob, downloadImportJobErrors, retryImportJobErrors } from './imports'
import { previewContracts } from './receivables'

afterEach(() => {
  vi.restoreAllMocks()
})

function mockJsonFetch(data: unknown) {
  return vi.fn().mockResolvedValue({
    ok: true,
    json: async () => ({ code: 0, message: 'ok', data, trace_id: 'trace-1' }),
  })
}

describe('previewContracts / previewFinanceRecords', () => {
  it('uploads the file to the contract preview endpoint', async () => {
    const fetchMock = mockJsonFetch({ job_id: 301, status: 'preview_pending' })
    vi.stubGlobal('fetch', fetchMock)

    const file = new File(['合同编号\nHT-1'], 'contracts.csv', { type: 'text/csv' })
    const result = await previewContracts('http://localhost:8080', 'token-1', 1, file)

    const [url, request] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('http://localhost:8080/api/v1/imports/contracts/preview')
    expect(request.method).toBe('POST')
    expect(request.body).toBeInstanceOf(FormData)
    expect(result.data.job_id).toBe(301)
  })

  it('uploads the file to the finance record preview endpoint', async () => {
    const fetchMock = mockJsonFetch({ job_id: 302, status: 'preview_pending' })
    vi.stubGlobal('fetch', fetchMock)

    const file = new File(['记录编号\nFR-1'], 'finance.csv', { type: 'text/csv' })
    const result = await previewFinanceRecords('http://localhost:8080', 'token-1', 1, file)

    const [url, request] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('http://localhost:8080/api/v1/imports/finance-records/preview')
    expect(request.method).toBe('POST')
    expect(result.data.job_id).toBe(302)
  })
})

describe('generic import job actions', () => {
  it('sends retry corrections merged per row', async () => {
    const fetchMock = mockJsonFetch({ job_id: 301, status: 'preview_pending' })
    vi.stubGlobal('fetch', fetchMock)

    await retryImportJobErrors('http://localhost:8080', 'token-1', 1, 301, [
      { row_no: 3, plan_amount: '400.00' },
    ])

    const [url, request] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('http://localhost:8080/api/v1/imports/301/retry-errors')
    expect(request.method).toBe('POST')
    expect(JSON.parse(String(request.body))).toEqual({
      rows: [{ row_no: 3, plan_amount: '400.00' }],
    })
  })

  it('confirms an import job without a body', async () => {
    const fetchMock = mockJsonFetch({ job_id: 301, status: 'confirmed' })
    vi.stubGlobal('fetch', fetchMock)

    const result = await confirmImportJob('http://localhost:8080', 'token-1', 1, 301)

    const [url, request] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('http://localhost:8080/api/v1/imports/301/confirm')
    expect(request.method).toBe('POST')
    expect(request.body).toBeUndefined()
    expect(result.data.status).toBe('confirmed')
  })

  it('downloads the error CSV as a blob', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      blob: async () => new Blob(['row_no,error\n2,金额格式不正确'], { type: 'text/csv' }),
    })
    vi.stubGlobal('fetch', fetchMock)

    const blob = await downloadImportJobErrors('http://localhost:8080', 'token-1', 1, 301)

    const [url] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('http://localhost:8080/api/v1/imports/301/errors/download')
    expect(blob).toBeInstanceOf(Blob)
  })
})
