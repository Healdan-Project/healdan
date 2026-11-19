// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { act, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { expectNoAxeViolations } from '../test/axe'
import { start } from '../main'

// AdminAppServlet answers someone who isn't an administrator with this app's own page, at the /admin address they asked
// for (admin spec 6.1): the page says so, and asks the server nothing.
describe('The User app under /admin', () => {
  let root: HTMLElement | undefined
  let unmount: (() => void) | undefined

  afterEach(() => {
    act(() => unmount?.())
    unmount = undefined
    root?.remove()
    vi.restoreAllMocks()
    window.history.replaceState(null, '', '/app/')
  })

  async function startAt(address: string, secure: boolean) {
    window.history.replaceState(null, '', address)
    root = document.body.appendChild(document.createElement('div'))
    await act(async () => {
      unmount = await start(root!, secure)
    })
    return root
  }

  it('draws the no-access panel, with a link to the User app, and asks the server nothing', async () => {
    const fetch = vi.spyOn(globalThis, 'fetch')
    const drawn = await startAt('/admin/x', true)

    expect(screen.getByRole('heading', { level: 1, name: "You don't have access to Healdan Admin." })).toBeTruthy()
    expect(screen.getByRole('link', { name: 'Open Healdan' }).getAttribute('href')).toBe('/app/')
    expect(screen.queryByRole('banner')).toBeNull()
    expect(fetch).not.toHaveBeenCalled()
    await expectNoAxeViolations(drawn)
  })

  it('draws it at the Admin app\'s front page, with its query, at /admin itself, and with a path parameter', async () => {
    for (const address of ['/admin/', '/admin/matters/1001.1?tab=workspace', '/admin', '/admin;x']) {
      const fetch = vi.spyOn(globalThis, 'fetch')
      await startAt(address, true)
      expect(screen.getByRole('heading', { level: 1, name: "You don't have access to Healdan Admin." })).toBeTruthy()
      expect(fetch).not.toHaveBeenCalled()
      act(() => unmount?.())
      unmount = undefined
      root?.remove()
      vi.restoreAllMocks()
    }
  })

  it("draws it before the page for an address that isn't secure, which it needs no server for either", async () => {
    const fetch = vi.spyOn(globalThis, 'fetch')
    await startAt('/admin/x', false)

    expect(screen.getByRole('heading', { level: 1, name: "You don't have access to Healdan Admin." })).toBeTruthy()
    expect(screen.queryByText('Healdan needs a secure address.')).toBeNull()
    expect(fetch).not.toHaveBeenCalled()
  })

  it('leaves an address that only starts with the same letters to the app', async () => {
    const fetch = vi.spyOn(globalThis, 'fetch')
    await startAt('/administrator/', false)

    expect(screen.queryByText("You don't have access to Healdan Admin.")).toBeNull()
    expect(screen.getByRole('heading', { level: 1, name: 'Healdan needs a secure address.' })).toBeTruthy()
    expect(fetch).not.toHaveBeenCalled()
  })
})
