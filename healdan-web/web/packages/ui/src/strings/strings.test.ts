// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { afterEach, describe, expect, it } from 'vitest'
import { plural, setLabels, t } from './index'

afterEach(() => setLabels({ matter: 'Matter', matters: 'Matters' }))

describe('t', () => {
  it('fills variables', () => {
    expect(t('filters.count', { n: 2 })).toBe('2 Filters')
  })

  it('rejects an unknown key at type-check time', () => {
    // @ts-expect-error not a key of the catalogue
    expect(() => t('no.such.key')).toThrow()
  })

  it('uses US spelling', () => {
    expect(t('favorites.title')).toBe('Favorites')
    expect(t('settings.color')).toBe('Color')
  })

  it('replaces the matter label in strings that use it', () => {
    expect(t('matters.new')).toBe('New Matter')
    setLabels({ matter: 'Project', matters: 'Projects' })
    expect(t('matters.new')).toBe('New Project')
    expect(t('matters.title')).toBe('Projects')
  })
})

describe('plural', () => {
  it('picks the form for the count', () => {
    expect(plural('documents', 1)).toBe('1 document')
    expect(plural('documents', 0)).toBe('0 documents')
    expect(plural('documents', 7)).toBe('7 documents')
  })

  it('groups thousands as US English does, in the count and in any other number it is given', () => {
    expect(plural('documents', 1234)).toBe('1,234 documents')
    expect(plural('documentsOf', 12500, { shown: 1200 })).toBe('1,200 of 12,500 documents')
    expect(plural('documentsMoreThan', 10000)).toBe('More than 10,000 documents')
  })

  it('leaves the numbers t() is given alone, as a doc # is never grouped', () => {
    expect(t('filters.count', { n: 10388 })).toBe('10388 Filters')
  })

  it('uses the configured label', () => {
    setLabels({ matter: 'Project', matters: 'Projects' })
    expect(plural('matters', 1)).toBe('1 Project')
    expect(plural('matters', 3)).toBe('3 Projects')
  })
})
