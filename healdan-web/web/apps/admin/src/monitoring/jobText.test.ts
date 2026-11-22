// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { describe, expect, it } from 'vitest'
import { intervalParts, intervalWords, MAX_INTERVAL_SECONDS, maxEvery, secondsOf } from './jobText'

describe("A LogicalDOC task's interval", () => {
  it('is said in the largest of seconds, minutes and hours that fits it whole', () => {
    expect([1, 45, 60, 90, 300, 1800, 3600, 5400, 7200, 86_400, MAX_INTERVAL_SECONDS].map(intervalParts)).toEqual([
      { every: 1, unit: 'seconds' },
      { every: 45, unit: 'seconds' },
      { every: 1, unit: 'minutes' },
      { every: 90, unit: 'seconds' },
      { every: 5, unit: 'minutes' },
      { every: 30, unit: 'minutes' },
      { every: 1, unit: 'hours' },
      { every: 90, unit: 'minutes' },
      { every: 2, unit: 'hours' },
      { every: 24, unit: 'hours' },
      { every: 8760, unit: 'hours' },
    ])
  })

  it('reads in words, one of a unit in the singular', () => {
    expect([1, 45, 60, 1800, 3600, 86_400, MAX_INTERVAL_SECONDS].map(intervalWords)).toEqual([
      'Every second',
      'Every 45 seconds',
      'Every minute',
      'Every 30 minutes',
      'Every hour',
      'Every 24 hours',
      'Every 8,760 hours',
    ])
  })
})

describe('What Every takes', () => {
  it("is the server's rule: a whole number of seconds from 1 to 31,536,000, a year, in the unit picked", () => {
    expect(MAX_INTERVAL_SECONDS).toBe(31_536_000)
    expect([maxEvery('seconds'), maxEvery('minutes'), maxEvery('hours')]).toEqual([31_536_000, 525_600, 8760])
    expect(secondsOf('1', 'seconds')).toBe(1)
    expect(secondsOf(' 30 ', 'minutes')).toBe(1800)
    expect(secondsOf('007', 'seconds')).toBe(7)
    expect(secondsOf('31536000', 'seconds')).toBe(31_536_000)
    expect(secondsOf('525600', 'minutes')).toBe(31_536_000)
    expect(secondsOf('8760', 'hours')).toBe(31_536_000)
  })

  it("refuses what the server would: nothing, 0, more than a year, and anything that isn't a whole number", () => {
    for (const [every, unit] of [
      ['', 'minutes'],
      ['  ', 'minutes'],
      ['0', 'seconds'],
      ['31536001', 'seconds'],
      ['525601', 'minutes'],
      ['8761', 'hours'],
      ['1.5', 'hours'],
      ['-5', 'seconds'],
      ['1e3', 'seconds'],
      ['30 minutes', 'minutes'],
    ] as const) {
      expect([every, unit, secondsOf(every, unit)]).toEqual([every, unit, undefined])
    }
  })
})
