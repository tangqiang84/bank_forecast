import { describe, expect, it } from 'vitest'
import { hasPermission } from './permission'
import type { User } from '../services/auth'

function user(permissions: string[]): User {
  return {
    id: 1,
    tenant_id: 1,
    login_name: 'demo',
    display_name: '演示',
    roles: [],
    permissions,
  }
}

describe('hasPermission', () => {
  it('returns true when the user holds the permission code', () => {
    expect(hasPermission(user(['matching:confirm']), 'matching:confirm')).toBe(true)
  })

  it('returns false when the user lacks the permission code', () => {
    expect(hasPermission(user(['matching:view']), 'matching:confirm')).toBe(false)
  })

  it('returns false for null user or missing permissions', () => {
    expect(hasPermission(null, 'dashboard:view')).toBe(false)
    expect(hasPermission(undefined, 'dashboard:view')).toBe(false)
    expect(hasPermission(user([]), 'dashboard:view')).toBe(false)
  })
})
