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
    root?.remove()
    vi.restoreAllMocks()
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
