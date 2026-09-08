import { describe, expect, it } from 'vitest'

import { formatCurrency } from './number'

describe('formatCurrency', () => {
  it('formats numeric values', () => {
    expect(formatCurrency(2865300)).toBe('2,865,300.00')
  })

  it('returns a placeholder for invalid values', () => {
    expect(formatCurrency('not-a-number')).toBe('-')
  })
})
