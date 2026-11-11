// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { describe, expect, it } from 'vitest'
import { formatDate, formatDateTime, formatVersion } from './format'

describe('formatDate', () => {
  it("shows today's time", () => {
    const now = new Date()
    now.setHours(9, 42, 0, 0)
    const expected = new Intl.DateTimeFormat(undefined, { hour: 'numeric', minute: '2-digit' }).format(now)
    expect(formatDate(now.toISOString())).toBe(expected)
  })

  it("shows another day's month, day and year", () => {
    const other = new Date(2026, 8, 29, 12, 0)
    const expected = new Intl.DateTimeFormat(undefined, { month: 'short', day: 'numeric', year: 'numeric' }).format(other)
    expect(formatDate(other.toISOString())).toBe(expected)
    expect(formatDate(other.toISOString())).toContain('2026')
  })

  it('shows both date and time in formatDateTime', () => {
    const other = new Date(2026, 8, 29, 14, 5)
    const text = formatDateTime(other.toISOString())
    expect(text).toContain('2026')
    expect(text).toMatch(/2:05/)
  })

  it('shows nothing for an empty or invalid value', () => {
    for (const bad of ['', 'not a date']) {
      expect(formatDate(bad)).toBe('')
      expect(formatDateTime(bad)).toBe('')
    }
  })
})

describe('formatVersion', () => {
  it('writes a version as Healdan numbers it: a whole version alone, a snapshot with its minor number', () => {
    expect(formatVersion('3.0')).toBe('3')
    expect(formatVersion('1.0')).toBe('1')
    expect(formatVersion('2.1')).toBe('2.1')
    expect(formatVersion('10.0')).toBe('10')
    expect(formatVersion('10.10')).toBe('10.10')
  })
})
