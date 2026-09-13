import { afterEach, describe, expect, it, vi } from 'vitest'

import { loadDashboardOverview } from './dashboard'

afterEach(() => {
  vi.restoreAllMocks()
})

describe('loadDashboardOverview', () => {
  it('loads dashboard overview data', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        code: 0,
        message: 'ok',
        data: {
          total_balance: '2865300.00',
          yesterday_net_inflow: '128400.00',
          pending_exceptions: 7,
          idle_accounts: 3,
          match_rate: 0.78,
          last_sync_at: '2026-09-09T08:30:00+08:00',
          top_receivables: [],
          recent_import_jobs: [],
          key_risks: [],
        },
        trace_id: 'trace-1',
      }),
    })

    vi.stubGlobal('fetch', fetchMock)

    const result = await loadDashboardOverview('http://localhost:8080', 'token-1', 1)

    expect(fetchMock).toHaveBeenCalledTimes(1)
    const [, request] = fetchMock.mock.calls[0] as [string, RequestInit]
    const headers = new Headers(request.headers)
    expect(headers.get('Authorization')).toBe('Bearer token-1')
    expect(headers.get('X-Tenant-Id')).toBe('1')
    expect(headers.get('X-Trace-Id')).toBeTruthy()
    expect(request.signal).toBeInstanceOf(AbortSignal)
    expect(result.data.total_balance).toBe('2865300.00')
    expect(result.trace_id).toBe('trace-1')
  })
})
