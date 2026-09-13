import { afterEach, describe, expect, it, vi } from 'vitest'

import { fetchBlob, fetchJson, HttpError } from './http'

afterEach(() => {
  vi.useRealTimers()
  vi.restoreAllMocks()
})

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

describe('fetchJson', () => {
  it('adds JSON content type and trace header', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ code: 0, message: 'ok', data: {}, trace_id: 'trace-1' }))
    vi.stubGlobal('fetch', fetchMock)

    await fetchJson('http://localhost:8080/api/v1/matching/exceptions/1/comment', {
      method: 'POST',
      headers: { Authorization: 'Bearer token-1', 'X-Tenant-Id': '1' },
      body: JSON.stringify({ text: '已核实' }),
    })

    const [, request] = fetchMock.mock.calls[0] as [string, RequestInit]
    const headers = new Headers(request.headers)
    expect(headers.get('Content-Type')).toBe('application/json')
    expect(headers.get('Authorization')).toBe('Bearer token-1')
    expect(headers.get('X-Tenant-Id')).toBe('1')
    expect(headers.get('X-Trace-Id')).toBeTruthy()
    expect(request.signal).toBeInstanceOf(AbortSignal)
  })

  it('throws a structured error when business code is non-zero', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({ code: 41002, message: '无权访问', data: null, trace_id: 'trace-api' })))

    let error: unknown
    try {
      await fetchJson('http://localhost:8080/api/v1/restricted', { retries: 0 })
    } catch (cause) {
      error = cause
    }

    expect(error).toBeInstanceOf(HttpError)
    const httpError = error as HttpError
    expect(httpError).toMatchObject({ kind: 'api', code: 41002, traceId: 'trace-api', status: 200 })
    expect(httpError.message).toBe('无权访问')
  })

  it('classifies 401 and emits an unauthorized event', async () => {
    const listener = vi.fn()
    window.addEventListener('auth:unauthorized', listener)
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({ code: 41001, message: '登录已过期', data: null, trace_id: 'trace-auth' }, 401)))

    await expect(fetchJson('http://localhost:8080/api/v1/auth/me', { retries: 0 })).rejects.toMatchObject({
      kind: 'unauthorized',
      code: 41001,
      status: 401,
      traceId: 'trace-auth',
    })
    expect(listener).toHaveBeenCalledTimes(1)
    window.removeEventListener('auth:unauthorized', listener)
  })

  it('classifies network errors and retries safe requests', async () => {
    const fetchMock = vi.fn()
      .mockRejectedValueOnce(new TypeError('Failed to fetch'))
      .mockResolvedValueOnce(jsonResponse({ code: 0, message: 'ok', data: {}, trace_id: 'trace-2' }))
    vi.stubGlobal('fetch', fetchMock)

    await fetchJson('http://localhost:8080/api/v1/dashboard/overview', { retryDelayMs: 0 })

    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('does not retry POST requests with network errors', async () => {
    const fetchMock = vi.fn().mockRejectedValue(new TypeError('Failed to fetch'))
    vi.stubGlobal('fetch', fetchMock)

    await expect(fetchJson('http://localhost:8080/api/v1/imports', { method: 'POST', body: '{}', retryDelayMs: 0 })).rejects.toMatchObject({ kind: 'network' })
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('classifies AbortController timeout', async () => {
    vi.useFakeTimers()
    const fetchMock = vi.fn((_url: string, init: RequestInit) => new Promise<Response>((_resolve, reject) => {
      init.signal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')), { once: true })
    }))
    vi.stubGlobal('fetch', fetchMock)

    const pending = expect(fetchJson('http://localhost:8080/api/v1/slow', { timeoutMs: 10, retries: 0 })).rejects.toMatchObject({ kind: 'timeout' })
    await vi.advanceTimersByTimeAsync(10)

    await pending
  })
})

describe('fetchBlob', () => {
  it('returns a blob and preserves custom headers', async () => {
    const blob = new Blob(['report'], { type: 'text/csv' })
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      headers: new Headers({ 'Content-Type': 'text/csv' }),
      blob: async () => blob,
    })
    vi.stubGlobal('fetch', fetchMock)

    const result = await fetchBlob('http://localhost:8080/api/v1/reports/1/download', {
      headers: { Authorization: 'Bearer token-1', 'X-Tenant-Id': '1' },
      retries: 0,
    })

    const [, request] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(new Headers(request.headers).get('Authorization')).toBe('Bearer token-1')
    expect(new Headers(request.headers).get('X-Tenant-Id')).toBe('1')
    expect(new Headers(request.headers).get('X-Trace-Id')).toBeTruthy()
    expect(result).toBe(blob)
  })

  it('uses the structured error message when a blob request fails', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({ code: 41002, message: '无权下载文件', trace_id: 'trace-blob' }, 403)))

    await expect(fetchBlob('http://localhost:8080/api/v1/reports/1/download', { retries: 0 })).rejects.toMatchObject({
      kind: 'http',
      message: '无权下载文件',
      traceId: 'trace-blob',
    })
  })
})
