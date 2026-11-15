// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { act, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { expectNoAxeViolations } from './test/axe'
import { start } from './main'

describe('Starting the app', () => {
  let root: HTMLElement | undefined
  let unmount: (() => void) | undefined

  afterEach(() => {
    act(() => unmount?.())
    unmount = undefined
    root?.remove()
    vi.restoreAllMocks()
    vi.useRealTimers()
  })

  it("draws the app with the default words once the server hasn't said what a matter is called within five seconds", async () => {
    vi.useFakeTimers()
    const signals: (AbortSignal | undefined)[] = []
    // A server that never answers: only an abort ends a request, as with a real fetch
    vi.spyOn(globalThis, 'fetch').mockImplementation((_input, init) => {
      const signal = init?.signal ?? undefined
      signals.push(signal)
      return new Promise<Response>((_resolve, reject) => signal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError'))))
    })
    root = document.body.appendChild(document.createElement('div'))
    let drawn = false
    void start(root, true).then((done) => {
      drawn = true
      unmount = done
    })

    await act(() => vi.advanceTimersByTimeAsync(4_999))
    expect(drawn).toBe(false)
    await act(() => vi.advanceTimersByTimeAsync(1))
    await act(() => vi.waitFor(() => expect(drawn).toBe(true)))
    expect(signals[0]?.aborted).toBe(true)
    expect(screen.getByRole('banner')).toBeTruthy()
  })

  it("at an address that isn't secure shows only its own page, with the Classic Interface, and asks the server nothing", async () => {
    const fetch = vi.spyOn(globalThis, 'fetch')
    root = document.body.appendChild(document.createElement('div'))
    await act(async () => {
      unmount = await start(root!, false)
    })
    expect(screen.getByRole('heading', { level: 1, name: 'Healdan needs a secure address.' })).toBeTruthy()
    expect(screen.getByText("Open it at an address that starts with https://, or use the Classic Interface, which works at this one.")).toBeTruthy()
    expect(screen.getByRole('link', { name: 'Classic Interface' }).getAttribute('href')).toBe('/frontend.jsp')
    expect(screen.queryByRole('banner')).toBeNull()
    expect(fetch).not.toHaveBeenCalled()
    await expectNoAxeViolations(root)
  })
})
