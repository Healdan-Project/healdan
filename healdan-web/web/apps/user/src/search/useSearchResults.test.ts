// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { describe, expect, it } from 'vitest'
import type { SortColumn } from '../views/useListing'
import { nextOffset, paramsOf } from './useSearchResults'

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

describe('paramsOf', () => {
  const ascending = (column: SortColumn) => ({ column, direction: 'ascending' as const })

  it("sends a sort the scope's search takes", () => {
    expect(paramsOf({ scope: 'matters', q: '1001', sort: ascending('client') })).toEqual({ scope: 'matters', q: '1001', sort: 'client', order: 'asc' })
    expect(paramsOf({ scope: 'emails', q: 'claim', sort: { column: 'sent', direction: 'descending' } })).toMatchObject({ sort: 'sent', order: 'desc' })
  })

  it("sends no sort the scope's search doesn't take, which the API would refuse", () => {
    expect(paramsOf({ scope: 'matters', q: '1001', sort: ascending('docNumber') })).toEqual({ scope: 'matters', q: '1001' })
    expect(paramsOf({ scope: 'clients', q: 'acme', sort: ascending('matter') })).toEqual({ scope: 'clients', q: 'acme' })
    expect(paramsOf({ scope: 'folders', q: 'corr', sort: ascending('from') })).toEqual({ scope: 'folders', q: 'corr' })
  })
})
