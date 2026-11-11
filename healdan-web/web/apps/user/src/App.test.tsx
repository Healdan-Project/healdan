// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { expectNoAxeViolations } from './test/axe'
import { folder, problem } from './test/fakeApi'
import { renderApp } from './test/renderApp'

describe('the addresses', () => {
  it.each([
    ['/recent/emails', 'Recent Emails'],
    ['/recent/folders', 'Recent Folders'],
    ['/recent/clients', 'Recent Clients'],
    ['/search?scope=documents&q=charter', 'Search'],
    ['/doc/10388', 'Document'],
    ['/doc/10388.2', 'Document'],
  ])('%s shows a view that is coming', async (path, title) => {
    await renderApp(path)
    expect(await screen.findByRole('heading', { level: 1, name: title })).toBeTruthy()
    expect(screen.getByText('Coming soon.')).toBeTruthy()
  })

  it('reads an unknown Recent kind or address as not found', async () => {
    await renderApp('/recent/bogus')
    expect(await screen.findByText("Not found, or you can't see it.")).toBeTruthy()
  })

  it('/folders/110 shows the folder', async () => {
    await renderApp('/folders/110', { 'GET /v1/folders/110': folder({ id: 110, name: '1001.1 – Charter Dispute', kind: 'matter' }) })
    expect(await screen.findByRole('heading', { level: 1, name: '1001.1 – Charter Dispute' })).toBeTruthy()
    expect(await within(screen.getByRole('main')).findByRole('grid')).toBeTruthy()
  })

  it.each(['/folders/abc', '/folders/1e3', '/folders/1234567890123456789'])('reads %s, which is no folder id, as not found without asking', async (path) => {
    const { api } = await renderApp(path)
    expect(within(screen.getByRole('main')).getByText("Not found, or you can't see it.")).toBeTruthy()
    expect(api.callsTo('GET /v1/folders/*')).toEqual([])
  })

  it.each(['/zzz', '/folders/', '/recent', '/doc'])('keeps the frame around %s, which matches no route', async (path) => {
    await renderApp(path)
    expect(screen.getByRole('banner')).toBeTruthy()
    expect(screen.getByRole('navigation', { name: 'Navigation' })).toBeTruthy()
    expect(within(screen.getByRole('main')).getByText("Not found, or you can't see it.")).toBeTruthy()
  })

  it('keeps the frame around every view', async () => {
    await renderApp('/doc/1')
    expect(screen.getByRole('banner')).toBeTruthy()
    expect(screen.getByRole('navigation', { name: 'Navigation' })).toBeTruthy()
    expect(screen.getByRole('main')).toBeTruthy()
  })

  it('passes axe on the start page, fully loaded', async () => {
    const { view } = await renderApp('/')
    await screen.findByRole('list', { name: 'Recent Documents' })
    await screen.findByRole('treegrid', { name: 'My Matters' })
    await screen.findByRole('button', { name: 'Priya Nair' })
    await expectNoAxeViolations(view.container)
  })
})

describe('signing out and errors', () => {
  it('shows SignedOut on a 401 from any request', async () => {
    window.history.replaceState(null, '', '/app/')
    await renderApp('/', { 'GET /v1/matters/mine': problem(401, 'unauthenticated') })
    expect(await screen.findByText("You've been signed out.")).toBeTruthy()
    expect(screen.getByRole('link', { name: 'Sign In' }).getAttribute('href')).toBe('/app/')
  })

  it('shows the storage banner on a 503 storage-unreachable, and no raw server text', async () => {
    await renderApp('/', { 'GET /v1/documents/recent': problem(503, 'storage-unreachable', 'java.io.IOException: bucket') })
    expect((await screen.findByRole('alert')).textContent).toBe('Files are unavailable for now. Try again in a few minutes.')
    expect(document.body.textContent).not.toContain('IOException')
    expect(screen.getByRole('button', { name: 'Try Again' })).toBeTruthy()
  })

  it('shows one banner at a time, and takes it away when the retry works', async () => {
    let down = true
    await renderApp('/', { 'GET /v1/documents/recent': () => (down ? problem(503, 'storage-unreachable') : { items: [], offset: 0, limit: 0, total: 0 }) })
    await screen.findByRole('alert')
    down = false
    fireEvent.click(screen.getByRole('button', { name: 'Try Again' }))
    await waitFor(() => expect(screen.queryByRole('alert')).toBeNull())
  })
})
