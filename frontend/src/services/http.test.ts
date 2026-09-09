import { afterEach, describe, expect, it, vi } from 'vitest'

import { fetchJson } from './http'

afterEach(() => {
  vi.restoreAllMocks()
})

describe('fetchJson', () => {
  it('keeps JSON content type when custom auth headers are supplied', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ code: 0, message: 'ok', data: {}, trace_id: 'trace-1' }),
    })
    vi.stubGlobal('fetch', fetchMock)

    await fetchJson('http://localhost:8080/api/v1/matching/exceptions/1/comment', {
      method: 'POST',
      headers: { Authorization: 'Bearer token-1', 'X-Tenant-Id': '1' },
      body: JSON.stringify({ text: '已核实' }),
    })

    expect(fetchMock).toHaveBeenCalledWith('http://localhost:8080/api/v1/matching/exceptions/1/comment', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: 'Bearer token-1',
        'X-Tenant-Id': '1',
      },
      body: JSON.stringify({ text: '已核实' }),
    })
  })
})
