// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { createMemoryHistory } from '@tanstack/react-router'
import { describe, expect, it } from 'vitest'
import { createAppRouter } from '../router'
import { activeCount, filterParams, filterSearch, noFilters, readFilters } from './useFilters'

describe('readFilters', () => {
  it('reads each filter from the address, the repeated ones as lists', () => {
    expect(readFilters({ type: ['pdf', 'word'], author: ['pnair'], class: ['Docs'], client: ['998'], matter: ['998.1'], edited: '7d' })).toEqual({
      type: ['pdf', 'word'],
      author: ['pnair'],
      class: ['Docs'],
      client: ['998'],
      matter: ['998.1'],
      edited: '7d',
    })
  })

  it('takes one value as a list of one, and each value once', () => {
    expect(readFilters({ type: 'pdf', author: ['a', 'a', ''] })).toMatchObject({ type: ['pdf'], author: ['a'] })
  })

  it('leaves out what the API would refuse, so a pasted address never turns a list into an error', () => {
    const read = readFilters({ type: ['pdf', 'folder', 'zip'], client: ['x', '998'], matter: ['998', '998.1'], edited: 'last-week', from: '2026-02-31' })
    expect(read).toEqual({ ...noFilters, type: ['pdf'], client: ['998'], matter: ['998.1'] })
  })

  it('lets a range win over a preset, and drops an end that comes before the start', () => {
    const both = readFilters({ edited: 'today', from: '2026-11-01', to: '2026-11-09' })
    expect(both.edited).toBeUndefined()
    expect(both).toMatchObject({ from: '2026-11-01', to: '2026-11-09' })
    const backwards = readFilters({ from: '2026-11-09', to: '2026-11-01' })
    expect(backwards.from).toBe('2026-11-09')
    expect(backwards.to).toBeUndefined()
  })
})

describe('activeCount and filterParams', () => {
  it('counts each kind of filter once, however many values it has, and the edit date once', () => {
    expect(activeCount(noFilters)).toBe(0)
    expect(activeCount({ ...noFilters, type: ['pdf', 'word'], author: ['pnair'], from: '2026-11-01', to: '2026-11-09' })).toBe(3)
  })

  it("gives the API's query, with repeated values as lists and nothing for what is unset", () => {
    expect(filterParams(noFilters)).toEqual({})
    expect(filterParams({ ...noFilters, type: ['pdf', 'word'], from: '2026-11-01' })).toEqual({ type: ['pdf', 'word'], from: '2026-11-01' })
    expect(filterSearch({ type: 'pdf', q: 'x' })).toEqual({ type: ['pdf'] })
  })
})

describe('the address', () => {
  async function at(path: string) {
    const router = createAppRouter(createMemoryHistory({ initialEntries: ['/app' + path] }))
    await router.load()
    return router
  }

  it('keeps repeated filters, the selection and the sort through a round trip', async () => {
    const router = await at('/folders/7?type=pdf&type=word&author=pnair&sel=10388&sort=author&order=desc')
    expect(router.state.location.search).toMatchObject({ type: ['pdf', 'word'], author: ['pnair'], sel: '10388', sort: 'author', order: 'desc' })
    await router.navigate({ to: '/folders/$id', params: { id: '7' }, search: (prev) => ({ ...prev, from: '2026-11-01' }) })
    expect(router.history.location.search).toBe('?type=pdf&type=word&author=pnair&sel=10388&sort=author&order=desc&from=2026-11-01')
  })
})
