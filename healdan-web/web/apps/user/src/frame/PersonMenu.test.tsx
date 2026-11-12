// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { fireEvent, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { expectNoAxeViolations } from '../test/axe'
import { problem } from '../test/fakeApi'
import { renderApp } from '../test/renderApp'
import { goTo } from './navigate'

vi.mock('./navigate', () => ({ goTo: vi.fn() }))

async function openMenu(name: string) {
  const trigger = await screen.findByRole('button', { name })
  fireEvent.keyDown(trigger, { key: 'Enter' })
  fireEvent.keyUp(trigger, { key: 'Enter' })
  return screen.findByRole('menu')
}

beforeEach(() => vi.mocked(goTo).mockClear())

describe('the person menu', () => {
  it('shows the name, Signed-in Devices, Classic Interface and Sign Out', async () => {
    await renderApp()
    const menu = await openMenu('Priya Nair')
    expect(menu.textContent).toContain('Priya Nair')
    const devices = await screen.findByRole('menuitem', { name: 'Signed-in Devices' })
    expect(devices.getAttribute('aria-disabled')).toBeNull()
    expect(screen.getByRole('menuitem', { name: 'Classic Interface' }).getAttribute('href')).toBe('/frontend.jsp')
    expect(screen.getByRole('menuitem', { name: 'Sign Out' })).toBeTruthy()
    await expectNoAxeViolations(document.body)
  })

  it('signs out: deletes the session, then goes to the sign-in page', async () => {
    const { api } = await renderApp()
    await openMenu('Priya Nair')
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Sign Out' }))
    await waitFor(() => expect(goTo).toHaveBeenCalledWith('/login.jsp'))
    expect(api.callsTo('DELETE /v1/session')).toHaveLength(1)
  })

  it('stays signed in, and says so, when the server refuses the sign-out from this page', async () => {
    await renderApp('/', { 'DELETE /v1/session': problem(403, 'web-session-refused') })
    await openMenu('Priya Nair')
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Sign Out' }))
    expect(await screen.findByText(/Healdan refused that from this page/)).toBeTruthy()
    expect(goTo).not.toHaveBeenCalled()
    expect(screen.queryByText("You've been signed out.")).toBeNull()
  })

  it('stays when the sign-out fails for a reason other than being signed out already', async () => {
    await renderApp('/', { 'DELETE /v1/session': problem(500, 'server-error') })
    await openMenu('Priya Nair')
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Sign Out' }))
    expect(await screen.findByText('Something went wrong.')).toBeTruthy()
    expect(goTo).not.toHaveBeenCalled()
  })
})

describe('the Help menu', () => {
  it('links to the User Guide and the notices, each in a new window', async () => {
    await renderApp()
    await openMenu('Help')
    const guide = screen.getByRole('menuitem', { name: 'User Guide' })
    expect(guide.getAttribute('href')).toBe('https://docs.healdan.com/user/')
    expect(guide.getAttribute('target')).toBe('_blank')
    expect(guide.getAttribute('rel')).toBe('noopener noreferrer')
    const notices = screen.getByRole('menuitem', { name: 'Open-Source Notices' })
    expect(notices.getAttribute('href')).toBe('/app/notices.txt')
    expect(notices.getAttribute('target')).toBe('_blank')
    expect(notices.getAttribute('rel')).toBe('noopener noreferrer')
  })
})
