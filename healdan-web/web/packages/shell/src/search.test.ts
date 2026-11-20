// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { describe, expect, it } from 'vitest'
import { parseSearch, stringifySearch } from './search'

describe("The apps' addresses' parameters", () => {
  it('are plain strings, not JSON', () => {
    expect(parseSearch('?sel=10388&q=%22charter%22')).toEqual({ sel: '10388', q: '"charter"' })
    expect(stringifySearch({ sel: '10388', page: 2 })).toBe('?sel=10388&page=2')
  })

  it('come as lists for the names that repeat, and as the last value for any other', () => {
    expect(parseSearch('?type=pdf&type=word&q=a&q=b', ['type', 'author'])).toEqual({ type: ['pdf', 'word'], q: 'b' })
    expect(parseSearch('?type=pdf', ['type'])).toEqual({ type: ['pdf'] })
    expect(parseSearch('?type=pdf&type=word')).toEqual({ type: 'word' })
  })

  it('repeat a list, and leave out what has no value', () => {
    expect(stringifySearch({ type: ['pdf', 'word'], q: '', sel: undefined, sort: null })).toBe('?type=pdf&type=word')
    expect(stringifySearch({})).toBe('')
  })
})
