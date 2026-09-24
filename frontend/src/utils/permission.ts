import type { User } from '../services/auth'

export function hasPermission(user: User | null | undefined, code: string): boolean {
  return Boolean(user && Array.isArray(user.permissions) && user.permissions.includes(code))
}
