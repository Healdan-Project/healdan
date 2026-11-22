// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { describe, expect, it } from 'vitest'
import type { adminComponents } from '@healdan/api'
import { createQueryClient, createSignedOut, ServicesProvider } from '@healdan/shell'
import { fakeApi, problem, type Routes } from '../test/fakeApi'
import { FOLLOW_SHORT_PAGES, useCursorList, type CursorQuery } from './useCursorList'

type A = adminComponents['schemas']
type AuditQuery = CursorQuery<'/v1/admin/audit'>

/** A row of the Audit Trail, by its number: only its id is read here */
function row(n: number): A['AuditRow'] {
  return {
    id: `d${n}`,
    when: '2026-11-19T10:41:00Z',
    who: 'Tom Reeve',
    kind: 'document',
    event: 'event.downloaded',
    what: 'Downloaded',
    detail: `# ${10000 + n}, Note ${n}.docx, version 1.0`,
    from: '192.0.2.12',
    docNumber: 10000 + n,
    folderId: 110,
    changes: null,
  }
}

/** A page: its rows by number, and where the next one starts */
function page(numbers: number[], nextCursor: string | null): A['AuditPage'] {
  return { items: numbers.map(row), nextCursor }
}

/** The Audit Trail's route, answering each cursor with its page from `pages`, the first page under '' */
function byCursor(pages: Record<string, A['AuditPage'] | Error | Promise<A['AuditPage']>>): Routes {
  return { 'GET /v1/admin/audit': ({ query }: { query?: Record<string, unknown> }) => pages[String(query?.cursor ?? '')] ?? problem(400, 'bad-request') }
}

const PAGE_SIZE = 3

/** The hook over the fake API, with the filters `query` until a rerender gives others */
function renderList(routes: Routes, query: AuditQuery = {}) {
  const api = fakeApi(routes)
  const queryClient = createQueryClient({ retry: false })
  const wrapper = ({ children }: { children: ReactNode }) => (
    <ServicesProvider api={api} signedOut={createSignedOut()}>
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    </ServicesProvider>
  )
  const hook = renderHook((props: { query: AuditQuery }) => useCursorList('/v1/admin/audit', props.query, PAGE_SIZE), { wrapper, initialProps: { query } })
  const ids = () => hook.result.current.items.map((one) => one.id)
  const cursors = () => api.callsTo('GET /v1/admin/audit').map((call) => call.query?.cursor)
  return { api, hook, ids, cursors }
}

/** A moment for anything the list would ask by itself to have been asked */
const settle = () => act(() => new Promise((resolve) => setTimeout(resolve, 20)))

describe('A list paged by cursor', () => {
  it('asks for the first page with its filters and the page size, then each next page by the cursor the last one gave, until there is none', async () => {
    const { api, hook, ids, cursors } = renderList(byCursor({ '': page([1, 2, 3], 'b'), b: page([4, 5, 6], 'c'), c: page([7], null) }), { kind: ['document'], q: 'reeve' })
    await waitFor(() => expect(ids()).toEqual(['d1', 'd2', 'd3']))
    expect(api.callsTo('GET /v1/admin/audit')[0]!.query).toEqual({ kind: ['document'], q: 'reeve', limit: PAGE_SIZE })
    expect(hook.result.current.hasMore).toBe(true)
    expect(hook.result.current.atEnd).toBe(false)
    expect(hook.result.current.isLoaded).toBe(true)

    act(() => hook.result.current.showMore())
    await waitFor(() => expect(ids()).toEqual(['d1', 'd2', 'd3', 'd4', 'd5', 'd6']))
    expect(api.callsTo('GET /v1/admin/audit')[1]!.query).toEqual({ kind: ['document'], q: 'reeve', limit: PAGE_SIZE, cursor: 'b' })

    // The last page is short, but has no cursor: the end, which the list says
    act(() => hook.result.current.showMore())
    await waitFor(() => expect(hook.result.current.atEnd).toBe(true))
    expect(ids()).toHaveLength(7)
    expect(hook.result.current.hasMore).toBe(false)
    act(() => hook.result.current.showMore())
    await settle()
    expect(cursors()).toEqual([undefined, 'b', 'c'])
  })

  it(`goes on by itself while a page comes back short with a cursor, ${FOLLOW_SHORT_PAGES} pages in a row at most, then waits for Show More, which goes on again`, async () => {
    const { hook, ids, cursors } = renderList(
      byCursor({
        '': page([1], 'b'),
        b: page([], 'c'),
        c: page([2], 'd'),
        d: page([], 'e'),
        // Asked for by Show More: short, so the next one follows, which is full, so nothing more is asked
        e: page([3], 'f'),
        f: page([4, 5, 6], 'g'),
        g: page([7, 8, 9], 'h'),
      }),
    )
    await waitFor(() => expect(cursors()).toEqual([undefined, 'b', 'c', 'd']))
    await settle()
    expect(cursors()).toEqual([undefined, 'b', 'c', 'd'])
    expect(ids()).toEqual(['d1', 'd2'])
    expect(hook.result.current.hasMore).toBe(true)
    expect(hook.result.current.isLoadingMore).toBe(false)

    act(() => hook.result.current.showMore())
    await waitFor(() => expect(ids()).toEqual(['d1', 'd2', 'd3', 'd4', 'd5', 'd6']))
    await settle()
    expect(cursors()).toEqual([undefined, 'b', 'c', 'd', 'e', 'f'])
    expect(hook.result.current.hasMore).toBe(true)
  })

  it('asks for the next page as the end comes into view only after a full page, never after a run that stopped over short pages', async () => {
    const { hook, ids, cursors } = renderList(byCursor({ '': page([1, 2, 3], 'b'), b: page([4], 'c'), c: page([], 'd'), d: page([], 'e'), e: page([], 'f'), f: page([5, 6, 7], 'g') }))
    await waitFor(() => expect(ids()).toEqual(['d1', 'd2', 'd3']))
    // A full page: the end coming into view reads on, as Show More would, and the short pages after it are followed
    act(() => hook.result.current.loadMoreOnScroll())
    await waitFor(() => expect(cursors()).toEqual([undefined, 'b', 'c', 'd', 'e']))
    await settle()
    expect(hook.result.current.hasMore).toBe(true)
    // The run stopped over short pages: the end, in view again, asks nothing, however often it says so
    act(() => hook.result.current.loadMoreOnScroll())
    act(() => hook.result.current.loadMoreOnScroll())
    await settle()
    expect(cursors()).toEqual([undefined, 'b', 'c', 'd', 'e'])
    // Show More reads on
    act(() => hook.result.current.showMore())
    await waitFor(() => expect(cursors()).toEqual([undefined, 'b', 'c', 'd', 'e', 'f']))
  })

  it('asks for nothing as the end comes into view after a read failed, until Try Again or Show More', async () => {
    let failing = false
    const routes: Routes = {
      'GET /v1/admin/audit': ({ query }: { query?: Record<string, unknown> }) => {
        if (query?.cursor === 'b' && failing) return problem(500, 'internal')
        return query?.cursor === undefined ? page([1, 2, 3], 'b') : page([4, 5, 6], 'c')
      },
    }
    const { hook, ids, cursors } = renderList(routes)
    await waitFor(() => expect(ids()).toEqual(['d1', 'd2', 'd3']))
    failing = true
    act(() => hook.result.current.loadMoreOnScroll())
    await waitFor(() => expect(hook.result.current.isError).toBe(true))
    act(() => hook.result.current.loadMoreOnScroll())
    await settle()
    expect(cursors()).toEqual([undefined, 'b'])
    failing = false
    act(() => hook.result.current.showMore())
    await waitFor(() => expect(ids()).toEqual(['d1', 'd2', 'd3', 'd4', 'd5', 'd6']))
    expect(cursors()).toEqual([undefined, 'b', 'b'])
  })

  it('offers more only once it has stopped going on by itself', async () => {
    let answer: (one: A['AuditPage']) => void = () => {}
    const { hook, cursors } = renderList(byCursor({ '': page([1], 'b'), b: new Promise((resolve) => (answer = resolve)) }))
    await waitFor(() => expect(cursors()).toEqual([undefined, 'b']))
    await waitFor(() => expect(hook.result.current.isLoadingMore).toBe(true))
    expect(hook.result.current.hasMore).toBe(false)
    await act(async () => answer(page([2, 3, 4], 'c')))
    await waitFor(() => expect(hook.result.current.hasMore).toBe(true))
    expect(hook.result.current.isLoadingMore).toBe(false)
  })

  it('keeps the rows it has when a read fails, and says so, whether a return to the window asked again or Show More did', async () => {
    let failing = false
    const routes: Routes = {
      'GET /v1/admin/audit': ({ query }: { query?: Record<string, unknown> }) => {
        if (failing) return problem(500, 'internal')
        return query?.cursor === undefined ? page([1, 2, 3], 'b') : page([4, 5, 6], 'c')
      },
    }
    const { hook, ids, cursors } = renderList(routes)
    await waitFor(() => expect(ids()).toEqual(['d1', 'd2', 'd3']))

    // A return to the window asks again, however fresh the answer (ASK_ON_RETURN)
    failing = true
    await act(async () => void window.dispatchEvent(new Event('focus')))
    await waitFor(() => expect(hook.result.current.isError).toBe(true))
    expect(cursors()).toEqual([undefined, undefined])
    expect(ids()).toEqual(['d1', 'd2', 'd3'])
    expect(hook.result.current.isLoaded).toBe(true)
    // Show More is still offered, and fails the same way, keeping the rows
    expect(hook.result.current.hasMore).toBe(true)
    act(() => hook.result.current.showMore())
    await waitFor(() => expect(cursors()).toEqual([undefined, undefined, 'b']))
    await waitFor(() => expect(hook.result.current.isLoadingMore).toBe(false))
    expect(hook.result.current.isError).toBe(true)
    expect(ids()).toEqual(['d1', 'd2', 'd3'])

    // Try Again
    failing = false
    act(() => hook.result.current.refetch())
    await waitFor(() => expect(hook.result.current.isError).toBe(false))
    expect(ids()).toEqual(['d1', 'd2', 'd3'])
    act(() => hook.result.current.showMore())
    await waitFor(() => expect(ids()).toEqual(['d1', 'd2', 'd3', 'd4', 'd5', 'd6']))
  })

  it('says so when the first page fails, with nothing to keep', async () => {
    const { hook } = renderList(byCursor({ '': problem(500, 'internal') }))
    await waitFor(() => expect(hook.result.current.isError).toBe(true))
    expect(hook.result.current.isLoaded).toBe(false)
    expect(hook.result.current.items).toEqual([])
    expect(hook.result.current.hasMore).toBe(false)
    expect(hook.result.current.atEnd).toBe(false)
  })

  it('starts afresh when its filters change: the old rows stay while the first page of the new ones comes, then go, and the new pages are followed afresh', async () => {
    let answer: (one: A['AuditPage']) => void = () => {}
    const routes: Routes = {
      'GET /v1/admin/audit': ({ query }: { query?: Record<string, unknown> }) => {
        if (query?.q === 'old') return query.cursor === undefined ? page([1, 2, 3], 'b') : page([4, 5, 6], 'c')
        // The new filter's: a short first page, held until the test answers it, then short ones
        if (query?.cursor === undefined) return new Promise((resolve) => (answer = resolve))
        return page([], `${String(query?.cursor)}x`)
      },
    }
    const { api, hook, ids, cursors } = renderList(routes, { q: 'old' })
    await waitFor(() => expect(ids()).toEqual(['d1', 'd2', 'd3']))
    act(() => hook.result.current.showMore())
    await waitFor(() => expect(ids()).toEqual(['d1', 'd2', 'd3', 'd4', 'd5', 'd6']))

    hook.rerender({ query: { q: 'new' } })
    await waitFor(() => expect(api.callsTo('GET /v1/admin/audit').at(-1)!.query).toEqual({ q: 'new', limit: PAGE_SIZE }))
    expect(ids()).toEqual(['d1', 'd2', 'd3', 'd4', 'd5', 'd6'])
    expect(hook.result.current.isStale).toBe(true)
    expect(hook.result.current.hasMore).toBe(false)

    await act(async () => answer(page([9], 'n')))
    await waitFor(() => expect(ids()).toEqual(['d9']))
    expect(hook.result.current.isStale).toBe(false)
    await waitFor(() => expect(cursors().slice(-4)).toEqual([undefined, 'n', 'nx', 'nxx']))
    await settle()
    expect(cursors().slice(-4)).toEqual([undefined, 'n', 'nx', 'nxx'])
    expect(hook.result.current.hasMore).toBe(true)
  })
})
