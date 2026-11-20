// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { expectNoAxeViolations } from '../test/axe'
import { dashboard, problem } from '../test/fakeApi'
import { renderApp } from '../test/renderApp'
import { setViewport } from '../test/viewport'

const NO_ACCESS = "You don't have access to Healdan Admin."

/** Polls run on intervals, which these tests move on by hand; nothing else is faked, so the app's own waits still run */
function fakeIntervals() {
  vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
}

async function waitSeconds(seconds: number) {
  await act(async () => void vi.advanceTimersByTime(seconds * 1000))
}

// Review Focus 1: an administrator removed from admin, or signed out, while the app is open. The next call is refused,
// the page says so, and no data keeps arriving (spec 6.1, 7.2).
describe('Refusals, wherever they come from', () => {
  it('a 401 from an admin call shows Signed Out in place of the whole frame', async () => {
    const { view } = await renderApp('/', { 'GET /v1/admin/dashboard': problem(401, 'unauthenticated') })
    expect(await screen.findByRole('heading', { level: 1, name: "You've been signed out." })).toBeTruthy()
    expect(screen.getByRole('link', { name: 'Sign In' })).toBeTruthy()
    expect(screen.queryByRole('banner')).toBeNull()
    expect(screen.queryByRole('navigation')).toBeNull()
    await expectNoAxeViolations(view.container)
  })

  it('a 403 admins-only shows the no-access panel, with the way to the User app, and asks nothing more', async () => {
    fakeIntervals()
    const { api, view } = await renderApp('/', { 'GET /v1/admin/dashboard': problem(403, 'admins-only') })
    expect(await screen.findByRole('heading', { level: 1, name: NO_ACCESS })).toBeTruthy()
    expect(screen.getByRole('link', { name: 'Open Healdan' }).getAttribute('href')).toBe('/app/')
    expect(screen.queryByRole('banner')).toBeNull()
    await expectNoAxeViolations(view.container)

    const asked = api.calls.length
    await waitSeconds(120)
    expect(api.calls).toHaveLength(asked)
  })

  it('someone taken out of admin while the Dashboard is open: the next poll is refused, the panel shows, and the polls stop', async () => {
    fakeIntervals()
    let removed = false
    const { api } = await renderApp('/', { 'GET /v1/admin/dashboard': () => (removed ? problem(403, 'admins-only') : dashboard) })
    expect(await screen.findByRole('heading', { level: 1, name: 'Dashboard' })).toBeTruthy()
    await screen.findByRole('region', { name: 'Needs Attention' })

    removed = true
    await waitSeconds(30)
    expect(await screen.findByRole('heading', { level: 1, name: NO_ACCESS })).toBeTruthy()
    expect(screen.queryByRole('heading', { name: 'Dashboard' })).toBeNull()

    const asked = api.calls.length
    await waitSeconds(120)
    expect(api.calls).toHaveLength(asked)
  })

  it('a 403 for another reason is that part\'s own error, and the frame stays', async () => {
    await renderApp('/', { 'GET /v1/admin/dashboard': problem(403, 'forbidden') })
    const attention = await screen.findByRole('region', { name: 'Needs Attention' })
    expect(await within(attention).findByText("You don't have permission to do that.")).toBeTruthy()
    expect(screen.getByRole('banner')).toBeTruthy()
    expect(screen.queryByText(NO_ACCESS)).toBeNull()
  })
})

describe('An address the app has no page for', () => {
  it('says so in the app\'s own words, inside the frame, with the way back to the Dashboard', async () => {
    const { view } = await renderApp('/no-such-page')
    expect(await screen.findByRole('heading', { level: 1, name: 'Page Not Found' })).toBeTruthy()
    expect(screen.getByText("There's no such page in Healdan Admin.")).toBeTruthy()
    const back = screen.getByRole('link', { name: 'Go to the Dashboard' })
    expect(back.getAttribute('href')).toBe('/admin/')
    expect(screen.getByRole('banner')).toBeTruthy()
    expect(screen.getByRole('navigation', { name: 'Navigation' })).toBeTruthy()
    await expectNoAxeViolations(view.container)

    await userEvent.click(back)
    expect(await screen.findByRole('heading', { level: 1, name: 'Dashboard' })).toBeTruthy()
  })
})

// Designed for 1,280 pixels and wider; below 1,024 the left pane folds away, as the User app's does (spec 2.2)
describe('The frame at a tablet width', () => {
  it('keeps the left pane open, with no button for it, at a desktop width', async () => {
    setViewport(1280)
    await renderApp('/')
    expect(await screen.findByRole('navigation', { name: 'Navigation' })).toBeTruthy()
    expect(screen.queryByRole('button', { name: 'Navigation' })).toBeNull()
  })

  it('folds the left pane behind a button of the top bar; Esc closes it and the focus goes back to the button', async () => {
    setViewport(768)
    const { view } = await renderApp('/')
    const button = await within(screen.getByRole('banner')).findByRole('button', { name: 'Navigation' })
    expect(screen.queryByRole('navigation', { name: 'Navigation' })).toBeNull()
    expect(button.getAttribute('aria-expanded')).toBe('false')

    await userEvent.click(button)
    const pane = await screen.findByRole('navigation', { name: 'Navigation' })
    expect(button.getAttribute('aria-expanded')).toBe('true')
    expect(within(pane).getByRole('link', { name: 'Dashboard' })).toBeTruthy()
    await expectNoAxeViolations(view.container)

    await userEvent.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByRole('navigation', { name: 'Navigation' })).toBeNull())
    expect(button.getAttribute('aria-expanded')).toBe('false')
    expect(document.activeElement).toBe(button)
  })
})
