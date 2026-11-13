// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { describe, expect, it } from 'vitest'
import type { components } from '@healdan/api'
import { formatDateTime } from '@healdan/ui'
import { holdLabel, holdSentence, holdState } from './checkout'

type Hold = components['schemas']['HoldView']

const ME = 5
const since = '2026-11-03T10:00:00Z'
const when = formatDateTime(since)
const you = { id: ME, name: 'Priya Nair' }
const alex = { id: 9, name: 'Alex Marsh' }
const macBook = { id: 'mac-1', name: 'Healdan Helper on MacBook', app: 'Excel' }

const checkout = (over: Partial<Hold> = {}): Hold => ({ kind: 'checkout', by: alex, since, device: null, ...over })
const label = (hold: Hold | null) => holdLabel(holdState(hold, ME))

// A list row's marker says who has the document and since when, as the details do, without what to do about it
describe('holdLabel', () => {
  it('is nothing for a free document', () => {
    expect(label(null)).toBeUndefined()
  })

  it('says who has a check-out, and since when', () => {
    expect(label(checkout())).toBe(`Checked out by Alex Marsh since ${when}.`)
    expect(label(checkout({ by: you }))).toBe(`Checked out by you since ${when}.`)
    expect(label(checkout({ by: you, device: macBook }))).toBe(`Checked out by you on Healdan Helper on MacBook since ${when}.`)
    expect(label(checkout({ since: null }))).toBe('Checked out by Alex Marsh.')
    expect(label(checkout({ by: null }))).toBe(`Checked out by someone since ${when}.`)
  })

  it('says who has a lock, and since when', () => {
    expect(label(checkout({ kind: 'lock' }))).toBe(`Locked by Alex Marsh since ${when}.`)
    expect(label(checkout({ kind: 'lock', by: you }))).toBe(`Locked by you since ${when}.`)
  })

  it('leaves out where it is released, which the details still say', () => {
    for (const hold of [checkout(), checkout({ by: you, device: macBook }), checkout({ kind: 'lock' })]) {
      const state = holdState(hold, ME)
      expect(holdLabel(state)).not.toMatch(/Release|released|Unlock/)
      expect(holdSentence(state)).toMatch(new RegExp(`^${holdLabel(state)!.replace(/[.()]/g, '\\$&')} (Release|It's released|Unlock)`))
    }
  })
})
