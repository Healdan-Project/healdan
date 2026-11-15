// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { act, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { setLabels, t } from '@healdan/ui'
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
    setLabels({ matter: 'Matter', matters: 'Matters' })
  })

  /**
   * A server whose GET /v1/server answers after `ms` with the firm's words, or never; every other request never ends but by
   * an abort, as with a real fetch
   */
  function slowServer(ms?: number) {
    vi.spyOn(globalThis, 'fetch').mockImplementation((input, init) => {
      const signal = init?.signal ?? undefined
      if (ms !== undefined && String(input).endsWith('/api/v1/server')) {
        const info = { labels: { matter: 'Case', matters: 'Cases' } }
        return new Promise<Response>((resolve) => setTimeout(() => resolve(new Response(JSON.stringify(info), { headers: { 'Content-Type': 'application/json' } })), ms))
      }
      return new Promise<Response>((_resolve, reject) => signal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError'))))
    })
  }

  function startNow(on: HTMLElement): () => boolean {
    let drawn = false
    void start(on, true).then((done) => {
      drawn = true
      unmount = done
    })
    return () => drawn
  }

  it("draws the app with the default words once the server hasn't said what a matter is called within five seconds", async () => {
    vi.useFakeTimers()
    slowServer()
    root = document.body.appendChild(document.createElement('div'))
    const drawn = startNow(root)

    await act(() => vi.advanceTimersByTimeAsync(4_999))
    expect(drawn()).toBe(false)
    await act(() => vi.advanceTimersByTimeAsync(1))
    await act(() => vi.waitFor(() => expect(drawn()).toBe(true)))
    expect(t('matters.title')).toBe('Matters')
    expect(screen.getByRole('banner')).toBeTruthy()
  })

  it('takes the firm\'s words when the server says them after the app was drawn, and draws it again', async () => {
    vi.useFakeTimers()
    slowServer(6_000)
    root = document.body.appendChild(document.createElement('div'))
    const drawn = startNow(root)
    await act(() => vi.advanceTimersByTimeAsync(5_000))
    await act(() => vi.waitFor(() => expect(drawn()).toBe(true)))
    expect(t('matters.title')).toBe('Matters')

    await act(() => vi.advanceTimersByTimeAsync(1_000))
    await act(() => vi.waitFor(() => expect(t('matters.title')).toBe('Cases')))
    expect(screen.getByRole('banner')).toBeTruthy()
  })

  it('draws nothing again when the words come after the app was taken away', async () => {
    vi.useFakeTimers()
    slowServer(6_000)
    root = document.body.appendChild(document.createElement('div'))
    const drawn = startNow(root)
    await act(() => vi.advanceTimersByTimeAsync(5_000))
    await act(() => vi.waitFor(() => expect(drawn()).toBe(true)))
    act(() => unmount?.())
    unmount = undefined

    await act(() => vi.advanceTimersByTimeAsync(1_000))
    await act(() => vi.waitFor(() => expect(t('matters.title')).toBe('Cases')))
    expect(root.childElementCount).toBe(0)
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
