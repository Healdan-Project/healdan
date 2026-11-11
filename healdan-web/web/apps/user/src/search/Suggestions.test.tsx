// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { describe, expect, it } from 'vitest'
import { SEARCH_ID, SUGGEST_MAX, matterLabel, suggestable, suggestionSections } from './Suggestions'

const answer = {
  matters: [{ id: 11, number: 1, name: 'Charter Dispute', client: { number: 1001, name: 'Saltmarsh Shipping Ltd' } }],
  documents: [{ docNumber: 10392, name: 'Charterparty.docx', matter: { id: 11, number: 1, display: '1001.1 – Charter Dispute' } }],
}

describe('suggestable', () => {
  it('needs two characters, trimmed', () => {
    expect(suggestable('c')).toBeUndefined()
    expect(suggestable(' c ')).toBeUndefined()
    expect(suggestable(' ch ')).toBe('ch')
  })

  it('leaves out a #, which goes straight to its document', () => {
    expect(suggestable('#10388')).toBeUndefined()
  })

  it('caps what is sent at 100 characters', () => {
    expect(suggestable('x'.repeat(150))).toHaveLength(SUGGEST_MAX)
  })
})

describe('suggestionSections', () => {
  it('lists matters, then documents, then the search itself named for the scope and the words', () => {
    const sections = suggestionSections(answer, 'emails', 'charter')
    expect(sections.map((section) => section.id)).toEqual(['matters', 'documents', 'run'])
    expect(sections[0]!.options[0]).toMatchObject({ id: 'm11', textValue: '1001.1 – Charter Dispute', meta: 'Saltmarsh Shipping Ltd' })
    expect(sections[1]!.options[0]).toMatchObject({ id: 'd10392', textValue: 'Charterparty.docx', meta: '# 10392 · 1001.1' })
    expect(sections[2]!.options[0]).toMatchObject({ id: SEARCH_ID, textValue: 'Search Emails for "charter"' })
    expect(sections[2]!.showTitle).toBe(false)
  })

  it('has only the search itself while nothing has come, or nothing matched', () => {
    expect(suggestionSections(undefined, 'documents', 'zz').map((section) => section.id)).toEqual(['run'])
    expect(suggestionSections({ matters: [], documents: [] }, 'documents', 'zz').map((section) => section.id)).toEqual(['run'])
  })

  it('names a matter as the app does everywhere', () => {
    expect(matterLabel(answer.matters[0]!)).toBe('1001.1 – Charter Dispute')
  })
})
