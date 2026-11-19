// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { act, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { expectNoAxeViolations } from './test/axe'
import { start } from './main'

describe('Starting the Admin app', () => {
  let root: HTMLElement | undefined
  let unmount: (() => void) | undefined

  afterEach(() => {
    act(() => unmount?.())
    unmount = undefined
    root?.remove()
    vi.restoreAllMocks()
  })

  it("at an address that isn't secure shows only the page that says so, with the Classic Interface, and asks the server nothing", async () => {
    const fetch = vi.spyOn(globalThis, 'fetch')
    root = document.body.appendChild(document.createElement('div'))
    await act(async () => {
      unmount = await start(root!, false)
    })
    expect(screen.getByRole('heading', { level: 1, name: 'Healdan needs a secure address.' })).toBeTruthy()
    expect(screen.getByRole('link', { name: 'Classic Interface' }).getAttribute('href')).toBe('/frontend.jsp')
    expect(screen.queryByRole('banner')).toBeNull()
    expect(fetch).not.toHaveBeenCalled()
    await expectNoAxeViolations(root)
  })

  it('at a secure address draws the app', async () => {
    // A server that doesn't answer: the frame and the Dashboard's heading draw all the same, and each part waits
    vi.spyOn(globalThis, 'fetch').mockImplementation(() => new Promise<Response>(() => {}))
    root = document.body.appendChild(document.createElement('div'))
    await act(async () => {
      unmount = await start(root!, true)
    })
    expect(await screen.findByRole('link', { name: 'Healdan Admin' })).toBeTruthy()
    expect(screen.getByRole('heading', { level: 1, name: 'Dashboard' })).toBeTruthy()
    expect(screen.queryByRole('heading', { name: 'Healdan needs a secure address.' })).toBeNull()
  })
})
