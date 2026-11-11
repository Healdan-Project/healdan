// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { describe, expect, it } from 'vitest'
import { createQueryClient } from '../queryClient'
import { ServicesProvider } from '../services'
import { createSignedOut } from '../signedOut'
import { children, document, fakeApi, folder, type FakeApi } from '../test/fakeApi'
import { useListing, type ListingOptions } from './useListing'

const all = [folder({ id: 8, name: 'Drafts' }), ...Array.from({ length: 249 }, (_, i) => document({ docNumber: 10000 + i, name: `D${i}.pdf` }))]

function serve(): FakeApi {
  return fakeApi({
    'GET /v1/folders/*/children': ({ query }: { query?: Record<string, unknown> }) => {
      const offset = Number(query?.offset)
      return children(all.slice(offset, offset + Number(query?.limit)), { offset, total: 250, documents: 249 })
    },
  })
}

function hook(api: FakeApi, options: ListingOptions) {
  const queryClient = createQueryClient({ retry: false })
  const wrapper = ({ children: inner }: { children: ReactNode }) => (
    <ServicesProvider api={api} signedOut={createSignedOut()}>
      <QueryClientProvider client={queryClient}>{inner}</QueryClientProvider>
    </ServicesProvider>
  )
  return renderHook((props: ListingOptions) => useListing(props), { wrapper, initialProps: options })
}

describe('useListing', () => {
  it('asks for the first 100 rows in the order asked for, and says how many there are', async () => {
    const api = serve()
    const { result } = hook(api, { folderId: 7, sort: { column: 'docNumber', direction: 'descending' } })
    await waitFor(() => expect(result.current.rows).toHaveLength(100))
    expect(result.current.total).toBe(250)
    expect(result.current.documents).toBe(249)
    expect(api.calls).toEqual([
      { method: 'GET', path: '/v1/folders/7/children', query: { sort: 'docNumber', order: 'desc', offset: 0, limit: 100 } },
    ])
  })

  it('loads the next 100 once however often it is asked while a page is coming, and stops at the end', async () => {
    const api = serve()
    const { result } = hook(api, { folderId: 7 })
    await waitFor(() => expect(result.current.rows).toHaveLength(100))
    act(() => {
      result.current.loadMore?.()
      result.current.loadMore?.()
    })
    await waitFor(() => expect(result.current.rows).toHaveLength(200))
    act(() => result.current.loadMore?.())
    await waitFor(() => expect(result.current.rows).toHaveLength(250))
    expect(result.current.loadMore).toBeUndefined()
    expect(api.calls.map((call) => call.query?.offset)).toEqual([0, 100, 200])
    // Rows keep their order across pages: the subfolder, then the documents
    expect(result.current.rows[0]?.type).toBe('folder')
    expect(result.current.rows[249]?.type).toBe('document')
  })

  it('keeps one copy of a row a later page repeats, as one does when the folder changes between pages', async () => {
    // A document filed meanwhile sorts before the loaded window, so the next page starts one row early
    const api = fakeApi({
      'GET /v1/folders/*/children': ({ query }: { query?: Record<string, unknown> }) => {
        const offset = Number(query?.offset)
        const from = offset === 0 ? 0 : offset - 1
        return children(all.slice(from, from + Number(query?.limit)), { offset, total: 251, documents: 250 })
      },
    })
    const { result } = hook(api, { folderId: 7 })
    await waitFor(() => expect(result.current.rows).toHaveLength(100))
    act(() => result.current.loadMore?.())
    await waitFor(() => expect(api.calls).toHaveLength(2))
    await waitFor(() => expect(result.current.rows).toHaveLength(199))
    const keys = result.current.rows.map((row) => (row.type === 'folder' ? `f${row.id}` : `d${row.docNumber}`))
    expect(new Set(keys).size).toBe(keys.length)
    expect(result.current.rows[99]).toBe(all[99])
  })

  it('keeps the rows it has while a new order loads, saying they are about to change', async () => {
    let release = () => {}
    const api = fakeApi({
      'GET /v1/folders/*/children': ({ query }: { query?: Record<string, unknown> }) => {
        const answer = children(all.slice(0, 100), { total: 250, documents: 249 })
        return query?.sort ? new Promise((resolve) => (release = () => resolve(answer))) : answer
      },
    })
    const { result, rerender } = hook(api, { folderId: 7 })
    await waitFor(() => expect(result.current.rows).toHaveLength(100))
    rerender({ folderId: 7, sort: { column: 'title', direction: 'descending' } })
    await waitFor(() => expect(api.calls).toHaveLength(2))
    expect(result.current.rows).toHaveLength(100)
    expect(result.current.documents).toBe(249)
    expect(result.current.isStale).toBe(true)
    expect(result.current.loadMore).toBeUndefined()
    act(() => release())
    await waitFor(() => expect(result.current.isStale).toBe(false))
  })

  it('starts again from the first page when the order changes', async () => {
    const api = serve()
    const { result, rerender } = hook(api, { folderId: 7 })
    await waitFor(() => expect(result.current.rows).toHaveLength(100))
    rerender({ folderId: 7, sort: { column: 'title', direction: 'descending' } })
    await waitFor(() => expect(api.calls).toHaveLength(2))
    expect(api.calls[1]?.query).toEqual({ sort: 'title', order: 'desc', offset: 0, limit: 100 })
  })

  it('asks for nothing when told not to', async () => {
    const api = serve()
    const { result } = hook(api, { folderId: 2, enabled: false })
    await new Promise((resolve) => setTimeout(resolve, 10))
    expect(api.calls).toHaveLength(0)
    expect(result.current.rows).toEqual([])
  })
})
