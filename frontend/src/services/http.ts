export type HttpErrorKind = 'timeout' | 'network' | 'unauthorized' | 'api' | 'http' | 'aborted' | 'parse'

export type HttpRequestInit = RequestInit & {
  retryDelayMs?: number
  retries?: number
  timeoutMs?: number
}

export type HttpErrorOptions = {
  cause?: unknown
  code?: number
  kind: HttpErrorKind
  status?: number
  traceId?: string
}

export class HttpError extends Error {
  readonly code?: number
  readonly kind: HttpErrorKind
  readonly retryable: boolean
  readonly status?: number
  readonly traceId?: string

  constructor(message: string, options: HttpErrorOptions) {
    super(message)
    this.name = 'HttpError'
    this.code = options.code
    this.kind = options.kind
    this.status = options.status
    this.traceId = options.traceId
    this.retryable = options.kind === 'timeout' || options.kind === 'network' || (options.status !== undefined && options.status >= 500)
    if (options.cause !== undefined) this.cause = options.cause
  }
}

type ApiEnvelope = {
  code?: unknown
  message?: unknown
  trace_id?: unknown
}

const DEFAULT_TIMEOUT_MS = 10_000
const DEFAULT_RETRIES = 2
const DEFAULT_RETRY_DELAY_MS = 200

function createTraceId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') return crypto.randomUUID().replaceAll('-', '')
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`
}

function isRetryableMethod(method: string): boolean {
  return ['GET', 'HEAD', 'OPTIONS'].includes(method.toUpperCase())
}

function headersWithDefaults(init: RequestInit, traceId: string): Headers {
  const headers = new Headers(init.headers)
  if (init.body !== undefined && !headers.has('Content-Type') && !(init.body instanceof FormData)) {
    headers.set('Content-Type', 'application/json')
  }
  if (!headers.has('X-Trace-Id')) headers.set('X-Trace-Id', traceId)
  return headers
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

async function readResponseBody(response: Response, preferJson = false): Promise<unknown> {
  const contentType = response.headers?.get?.('content-type') ?? ''
  if (preferJson || contentType.includes('application/json')) {
    if (typeof response.json === 'function') return response.json()
  }
  if (typeof response.text !== 'function') return null
  const text = await response.text()
  if (!text) return null
  try { return JSON.parse(text) as unknown } catch { return text }
}

function messageFromBody(body: unknown, fallback: string): string {
  if (isRecord(body) && typeof body.message === 'string' && body.message.trim()) return body.message
  return fallback
}

function traceIdFrom(response: Response, body: unknown, fallback: string): string {
  if (isRecord(body) && typeof body.trace_id === 'string' && body.trace_id) return body.trace_id
  return response.headers?.get?.('X-Trace-Id') ?? fallback
}

function notifyUnauthorized(traceId: string): void {
  if (typeof window !== 'undefined') {
    window.dispatchEvent(new CustomEvent('auth:unauthorized', { detail: { trace_id: traceId } }))
  }
}

function delay(milliseconds: number): Promise<void> {
  return new Promise((resolve) => window.setTimeout(resolve, milliseconds))
}

async function request<T>(url: string, init: HttpRequestInit, parse: (response: Response, body: unknown) => Promise<T>, expectJson = false): Promise<T> {
  const { retryDelayMs = DEFAULT_RETRY_DELAY_MS, retries = DEFAULT_RETRIES, timeoutMs = DEFAULT_TIMEOUT_MS, signal: externalSignal, ...requestInit } = init
  const method = (requestInit.method ?? 'GET').toUpperCase()
  const traceId = new Headers(requestInit.headers).get('X-Trace-Id') ?? createTraceId()
  const maxAttempts = isRetryableMethod(method) ? Math.max(0, retries) + 1 : 1

  for (let attempt = 0; attempt < maxAttempts; attempt += 1) {
    const controller = new AbortController()
    let timedOut = false
    const timeout = window.setTimeout(() => {
      timedOut = true
      controller.abort()
    }, timeoutMs)
    const abortExternal = () => controller.abort()
    externalSignal?.addEventListener('abort', abortExternal, { once: true })

    try {
      const response = await fetch(url, {
        ...requestInit,
        headers: headersWithDefaults(requestInit, traceId),
        signal: controller.signal,
      })
      const body = !response.ok || expectJson ? await readResponseBody(response, expectJson) : null
      const responseTraceId = traceIdFrom(response, body, traceId)
      const envelope = isRecord(body) ? body as ApiEnvelope : null
      const code = typeof envelope?.code === 'number' ? envelope.code : undefined

      if (response.status === 401) {
        notifyUnauthorized(responseTraceId)
        throw new HttpError('登录状态已失效，请重新登录', { code, kind: 'unauthorized', status: 401, traceId: responseTraceId })
      }
      if (!response.ok) {
        throw new HttpError(messageFromBody(body, `请求失败（HTTP ${response.status}）`), {
          code,
          kind: 'http',
          status: response.status,
          traceId: responseTraceId,
        })
      }
      if (code !== undefined && code !== 0) {
        throw new HttpError(messageFromBody(body, '业务请求失败'), { code, kind: 'api', status: response.status, traceId: responseTraceId })
      }
      return await parse(response, body)
    } catch (cause) {
      if (cause instanceof HttpError) {
        if (cause.retryable && attempt < maxAttempts - 1) {
          await delay(retryDelayMs * 2 ** attempt)
          continue
        }
        throw cause
      }
      const parseError = cause instanceof SyntaxError
      const error = new HttpError(
        timedOut ? '请求超时，请稍后重试' : externalSignal?.aborted ? '请求已取消' : parseError ? '服务响应格式错误' : '网络连接失败，请检查网络后重试',
        { cause, kind: timedOut ? 'timeout' : externalSignal?.aborted ? 'aborted' : parseError ? 'parse' : 'network', traceId },
      )
      if (error.retryable && attempt < maxAttempts - 1) {
        await delay(retryDelayMs * 2 ** attempt)
        continue
      }
      throw error
    } finally {
      window.clearTimeout(timeout)
      externalSignal?.removeEventListener('abort', abortExternal)
    }
  }

  throw new HttpError('请求失败，请稍后重试', { kind: 'network', traceId })
}

export function fetchJson<T>(url: string, init: HttpRequestInit = {}): Promise<T> {
  return request(url, init, async (_response, body) => {
    if (body === null) throw new HttpError('响应内容为空', { kind: 'parse' })
    return body as T
  }, true)
}

export function fetchMultipart<T>(url: string, formData: FormData, token: string, tenantId: number): Promise<T> {
  return fetchJson<T>(url, {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${token}`,
      'X-Tenant-Id': String(tenantId),
    },
    body: formData,
  })
}

export function fetchBlob(url: string, init: HttpRequestInit = {}): Promise<Blob> {
  return request(url, init, async (response) => response.blob())
}
