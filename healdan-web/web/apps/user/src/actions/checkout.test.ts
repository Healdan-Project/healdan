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

const checkout = (over: Partial<Hold> = {}): Hold => ({ kind: 'checkout', by: alex, since, device: null, heldOn: null, ...over })
const label = (hold: Hold | null) => holdLabel(holdState(hold, ME))
const sentence = (hold: Hold | null) => holdSentence(holdState(hold, ME))

// A list row's marker says who has the document, and nothing more: no time, no computer and no full stop (the owner's
// dogfooding punch list, 2026-11-14). The details' Check-Out Info has the rest.
describe('holdLabel', () => {
  it('is nothing for a free document', () => {
    expect(label(null)).toBeUndefined()
  })

  it('says who has a check-out', () => {
    expect(label(checkout())).toBe('Checked out by Alex Marsh')
    expect(label(checkout({ by: you }))).toBe('Checked out by you')
    expect(label(checkout({ by: you, device: macBook }))).toBe('Checked out by you')
    expect(label(checkout({ since: null }))).toBe('Checked out by Alex Marsh')
    expect(label(checkout({ by: null }))).toBe('Checked out by someone')
    expect(label(checkout({ by: { id: 9, name: null } }))).toBe('Checked out by someone')
  })

  it('says who has a lock', () => {
    expect(label(checkout({ kind: 'lock' }))).toBe('Locked by Alex Marsh')
    expect(label(checkout({ kind: 'lock', by: you }))).toBe('Locked by you')
    expect(label(checkout({ kind: 'lock', by: null }))).toBe('Locked by someone')
  })

  it('names neither the time nor the computer, nor where the document is released', () => {
    for (const hold of [checkout(), checkout({ by: you, device: macBook }), checkout({ kind: 'lock' })]) {
      expect(label(hold)).not.toMatch(/since|MacBook|Release|released|Unlock|\.$/)
    }
  })
})

// A question or a refusal says why it can't be done: who has it and since when, as a sentence, and never where it's
// released, which the details no longer say either
describe('holdSentence', () => {
  it('is nothing for a free document', () => {
    expect(sentence(null)).toBeUndefined()
  })

  it('says who has it and since when', () => {
    expect(sentence(checkout())).toBe(`Checked out by Alex Marsh since ${when}.`)
    expect(sentence(checkout({ by: you }))).toBe(`Checked out by you since ${when}.`)
    expect(sentence(checkout({ by: you, device: macBook }))).toBe(`Checked out by you on Healdan Helper on MacBook since ${when}.`)
    expect(sentence(checkout({ since: null }))).toBe('Checked out by Alex Marsh.')
    expect(sentence(checkout({ kind: 'lock' }))).toBe(`Locked by Alex Marsh since ${when}.`)
    expect(sentence(checkout({ kind: 'lock', by: you }))).toBe(`Locked by you since ${when}.`)
  })

  it('never says where it is released', () => {
    for (const hold of [checkout(), checkout({ by: you, device: macBook }), checkout({ kind: 'lock' })]) {
      expect(sentence(hold)).not.toMatch(/Release|released|Unlock|Classic Interface/)
    }
  })
})
