// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { act, render, waitFor } from '@testing-library/react'
import { QueryClientProvider, useQuery } from '@tanstack/react-query'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient, listenForReturn } from './queryClient'

// What the app shows stands in for push updates by asking again when the person comes back to its window (spec 10.2):
// from another app, whose window may have left the browser's on screen, as well as from another tab.

let asked = new Map<string, number>()
const count = (name: string) => asked.get(name) ?? 0

function Watching({ name, optOut = false }: { name: string; optOut?: boolean }) {
  useQuery({
    queryKey: [name],
    queryFn: () => {
      asked.set(name, count(name) + 1)
      return count(name)
    },
    // Only set when it opts out: an option given as undefined would hide the app's default
    ...(optOut ? { refetchOnWindowFocus: false } : {}),
  })
  return null
}

function renderWatching(...watching: { name: string; optOut?: boolean }[]) {
  const client = createQueryClient({ retry: false })
  render(
    <QueryClientProvider client={client}>
      {watching.map((one) => (
        <Watching key={one.name} {...one} />
      ))}
    </QueryClientProvider>,
  )
  return client
}

const comeBack = () => act(() => void window.dispatchEvent(new Event('focus')))
const settle = () => act(() => new Promise((resolve) => setTimeout(resolve, 20)))

function pretendHidden() {
  vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('hidden')
}

afterEach(() => {
  asked = new Map()
  vi.restoreAllMocks()
})

describe('coming back to the window', () => {
  it('asks again for what is on screen when the window gets the focus back, however fresh its answer is', async () => {
    const client = renderWatching({ name: 'document' })
    await waitFor(() => expect(count('document')).toBe(1))
    // Asked a moment ago: well inside the 30 seconds that moving between views keeps an answer
    expect(client.getQueryCache().find({ queryKey: ['document'] })?.isStaleByTime(30_000)).toBe(false)

    comeBack()
    await waitFor(() => expect(count('document')).toBe(2))
  })

  it('leaves alone a query that opts out', async () => {
    renderWatching({ name: 'document' }, { name: 'search', optOut: true })
    await waitFor(() => expect(count('document') + count('search')).toBe(2))

    comeBack()
    await waitFor(() => expect(count('document')).toBe(2))
    await settle()
    expect(count('search')).toBe(1)
  })

  it('asks nothing on a focus while the page is hidden', async () => {
    renderWatching({ name: 'document' })
    await waitFor(() => expect(count('document')).toBe(1))

    pretendHidden()
    comeBack()
    await settle()
    expect(count('document')).toBe(1)
  })

  it('still asks again when a hidden tab shows again', async () => {
    renderWatching({ name: 'document' })
    await waitFor(() => expect(count('document')).toBe(1))

    act(() => void document.dispatchEvent(new Event('visibilitychange', { bubbles: true })))
    await waitFor(() => expect(count('document')).toBe(2))
  })
})

describe('listenForReturn', () => {
  it('stops listening to both events once it is cleaned up', () => {
    const onFocus = vi.fn()
    const stop = listenForReturn(onFocus)
    window.dispatchEvent(new Event('focus'))
    document.dispatchEvent(new Event('visibilitychange', { bubbles: true }))
    expect(onFocus).toHaveBeenCalledTimes(2)

    stop()
    window.dispatchEvent(new Event('focus'))
    document.dispatchEvent(new Event('visibilitychange', { bubbles: true }))
    expect(onFocus).toHaveBeenCalledTimes(2)
  })

  it('hears only the window itself getting the focus, not a field in the page', () => {
    const onFocus = vi.fn()
    const stop = listenForReturn(onFocus)
    const field = document.body.appendChild(document.createElement('input'))
    field.focus()
    field.dispatchEvent(new FocusEvent('focus'))
    expect(onFocus).not.toHaveBeenCalled()
    field.remove()
    stop()
  })
})
