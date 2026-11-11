// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { describe, expect, it } from 'vitest'
import { formatDate, formatDateTime, formatDay, formatSize, formatVersion } from './format'

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

describe('formatDay', () => {
  it('shows a day as the person reads it, never moved by the time zone', () => {
    expect(formatDay('2026-11-02')).toBe('Nov 2, 2026')
    expect(formatDay('2026-01-01')).toBe('Jan 1, 2026')
  })

  it('shows nothing for what is not a day', () => {
    expect(formatDay('soon')).toBe('')
    expect(formatDay('')).toBe('')
  })
})

describe('formatSize', () => {
  it('shows a size in the units people read', () => {
    expect(formatSize(0)).toBe('0 B')
    expect(formatSize(512)).toBe('512 B')
    expect(formatSize(1023)).toBe('1,023 B')
    expect(formatSize(1024)).toBe('1 KB')
    expect(formatSize(214_000)).toBe('209 KB')
    expect(formatSize(1_468_006)).toBe('1.4 MB')
    expect(formatSize(10 * 1024 * 1024)).toBe('10 MB')
    expect(formatSize(3 * 1024 ** 3)).toBe('3 GB')
  })

  it('shows nothing for what is not a size', () => {
    expect(formatSize(-1)).toBe('')
    expect(formatSize(Number.NaN)).toBe('')
  })
})
