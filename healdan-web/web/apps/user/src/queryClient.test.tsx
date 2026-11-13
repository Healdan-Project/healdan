// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { act, render, waitFor } from '@testing-library/react'
import { QueryClientProvider, useInfiniteQuery, useQuery, type QueryClient } from '@tanstack/react-query'
import { useEffect, type ReactNode } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ASK_ON_RETURN, createQueryClient, listenForReturn } from './queryClient'
import { comeBack, settle } from './test/comeBack'

// What shows a document's state stands in for push updates by asking again when the person comes back to the window
// (spec 10.2): from another app, whose window may have left the browser's on screen, as well as from another tab.

let asked = new Map<string, number>()
const count = (name: string) => asked.get(name) ?? 0
const ask = (name: string) => {
  asked.set(name, count(name) + 1)
  return count(name)
}

interface Watched {
  name: string
  /** Whether it shows a document's state, which each return asks again */
  documentState?: boolean
}

function Watching({ name, documentState = false }: Watched) {
  useQuery({ queryKey: [name], queryFn: () => ask(name), ...(documentState ? { meta: ASK_ON_RETURN } : {}) })
  return null
}

// A paged list of document state, which loads its second page at once when `pages` is 2
function Paged({ pages }: { pages: number }) {
  const query = useInfiniteQuery({
    queryKey: ['paged'],
    queryFn: ({ pageParam }) => {
      ask('paged')
      return pageParam
    },
    initialPageParam: 1,
    getNextPageParam: (last) => last + 1,
    meta: ASK_ON_RETURN,
  })
  const loaded = query.data?.pages.length ?? 0
  const { fetchNextPage, isFetching } = query
  useEffect(() => {
    if (loaded > 0 && loaded < pages && !isFetching) void fetchNextPage()
  }, [loaded, pages, isFetching, fetchNextPage])
  return null
}

function renderWith(children: ReactNode): QueryClient {
  const client = createQueryClient({ retry: false })
  render(<QueryClientProvider client={client}>{children}</QueryClientProvider>)
  return client
}

const renderWatching = (...watching: Watched[]) => renderWith(watching.map((one) => <Watching key={one.name} {...one} />))

function pretendHidden() {
  vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('hidden')
}

afterEach(() => {
  asked = new Map()
  vi.restoreAllMocks()
})

describe('coming back to the window', () => {
  it("asks again for what shows a document's state when the window gets the focus back, however fresh its answer is", async () => {
    const client = renderWatching({ name: 'document', documentState: true })
    await waitFor(() => expect(count('document')).toBe(1))
    // Asked a moment ago: well inside the 30 seconds that moving between views keeps an answer
    expect(client.getQueryCache().find({ queryKey: ['document'] })?.isStaleByTime(30_000)).toBe(false)

    comeBack()
    await waitFor(() => expect(count('document')).toBe(2))
  })

  it('leaves anything else to its 30 seconds', async () => {
    renderWatching({ name: 'document', documentState: true }, { name: 'me' })
    await waitFor(() => expect(count('document') + count('me')).toBe(2))

    comeBack()
    await waitFor(() => expect(count('document')).toBe(2))
    await settle()
    expect(count('me')).toBe(1)
  })

  it('leaves a list scrolled past its first page to its 30 seconds, as it would ask for every page', async () => {
    renderWith(
      <>
        <Paged pages={2} />
        <Watching name="document" documentState />
      </>,
    )
    await waitFor(() => expect(count('paged')).toBe(2))

    comeBack()
    await waitFor(() => expect(count('document')).toBe(2))
    await settle()
    expect(count('paged')).toBe(2)
  })

  it('asks again for a list of one page', async () => {
    renderWith(<Paged pages={1} />)
    await waitFor(() => expect(count('paged')).toBe(1))

    comeBack()
    await waitFor(() => expect(count('paged')).toBe(2))
  })

  it('asks nothing on a focus while the page is hidden', async () => {
    renderWatching({ name: 'document', documentState: true })
    await waitFor(() => expect(count('document')).toBe(1))

    pretendHidden()
    comeBack()
    await settle()
    expect(count('document')).toBe(1)
  })

  it('still asks again when a hidden tab shows again', async () => {
    renderWatching({ name: 'document', documentState: true })
    await waitFor(() => expect(count('document')).toBe(1))

    act(() => void document.dispatchEvent(new Event('visibilitychange', { bubbles: true })))
    await waitFor(() => expect(count('document')).toBe(2))
  })
})

describe('listenForReturn', () => {
  it('stops listening to both events once it is cleaned up', () => {
    const onReturn = vi.fn()
    const stop = listenForReturn(onReturn)
    window.dispatchEvent(new Event('focus'))
    document.dispatchEvent(new Event('visibilitychange', { bubbles: true }))
    expect(onReturn).toHaveBeenCalledTimes(2)

    stop()
    window.dispatchEvent(new Event('focus'))
    document.dispatchEvent(new Event('visibilitychange', { bubbles: true }))
    expect(onReturn).toHaveBeenCalledTimes(2)
  })

  it('hears only the window itself getting the focus, not a field in the page', () => {
    const onReturn = vi.fn()
    const stop = listenForReturn(onReturn)
    const field = document.body.appendChild(document.createElement('input'))
    field.focus()
    field.dispatchEvent(new FocusEvent('focus'))
    expect(onReturn).not.toHaveBeenCalled()
    field.remove()
    stop()
  })

  // An email's body is a frame: clicking into it blurs the window, and clicking back into the page focuses it again
  it('takes the focus coming back from a frame in the page for no return, and the next one for a return', async () => {
    const onReturn = vi.fn()
    const stop = listenForReturn(onReturn)
    const frame = document.body.appendChild(document.createElement('iframe'))
    const row = document.body.appendChild(document.createElement('button'))
    frame.focus()
    expect(document.activeElement).toBe(frame)
    window.dispatchEvent(new Event('blur'))
    await new Promise((resolve) => setTimeout(resolve))
    row.focus()
    window.dispatchEvent(new Event('focus'))
    expect(onReturn).not.toHaveBeenCalled()

    // Then away to another app, and back
    window.dispatchEvent(new Event('blur'))
    await new Promise((resolve) => setTimeout(resolve))
    window.dispatchEvent(new Event('focus'))
    expect(onReturn).toHaveBeenCalledTimes(1)
    frame.remove()
    row.remove()
    stop()
  })
})
