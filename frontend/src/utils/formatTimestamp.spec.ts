import { describe, expect, it } from 'vitest'

import { formatTimestamp } from './formatTimestamp'

describe('formatTimestamp', () => {
  it('formats minute and second timestamps', () => {
    expect(formatTimestamp(65_000)).toBe('01:05')
  })

  it('includes hours when needed', () => {
    expect(formatTimestamp(3_661_000)).toBe('01:01:01')
  })
})
