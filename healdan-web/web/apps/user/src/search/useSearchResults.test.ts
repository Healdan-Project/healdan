// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { describe, expect, it } from 'vitest'
import { nextOffset } from './useSearchResults'

const items = (n: number) => Array.from({ length: n }, () => ({}))

describe('nextOffset', () => {
  it('goes on while a counted search has rows it has not loaded', () => {
    expect(nextOffset({ items: items(100), total: 250 }, 100)).toBe(100)
    expect(nextOffset({ items: items(100), total: 250 }, 200)).toBe(200)
  })

  it('ends a counted search at its total, even when the page is full', () => {
    expect(nextOffset({ items: items(100), total: 200 }, 200)).toBeUndefined()
    expect(nextOffset({ items: items(50), total: 150 }, 150)).toBeUndefined()
  })

  it('ends a search the server stopped counting at the first short page, and goes on while pages are full', () => {
    expect(nextOffset({ items: items(100), total: null }, 100)).toBe(100)
    expect(nextOffset({ items: items(100) }, 300)).toBe(300)
    expect(nextOffset({ items: items(20), total: null }, 120)).toBeUndefined()
  })

  it('ends on an empty page, whatever was counted', () => {
    expect(nextOffset({ items: [], total: 500 }, 100)).toBeUndefined()
    expect(nextOffset({ items: [], total: null }, 100)).toBeUndefined()
  })
})
