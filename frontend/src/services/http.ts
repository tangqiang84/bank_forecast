export async function fetchJson<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, {
    headers: {
      'Content-Type': 'application/json',
      ...(init?.headers ?? {}),
    },
    ...init,
  })

  if (!response.ok) {
    const body = await response.json().catch(() => null) as { message?: string } | null
    throw new Error(body?.message ?? `HTTP ${response.status}`)
  }

  return (await response.json()) as T
}

export async function fetchMultipart<T>(url: string, formData: FormData, token: string, tenantId: number): Promise<T> {
  const response = await fetch(url, {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${token}`,
      'X-Tenant-Id': String(tenantId),
    },
    body: formData,
  })

  if (!response.ok) {
    const body = await response.json().catch(() => null) as { message?: string } | null
    throw new Error(body?.message ?? `HTTP ${response.status}`)
  }

  return (await response.json()) as T
}
