// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { QueryClientProvider } from '@tanstack/react-query'
import { createMemoryHistory, createRootRoute, createRoute, createRouter, RouterProvider } from '@tanstack/react-router'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { createQueryClient, createSignedOut, ServicesProvider } from '@healdan/shell'
import { ADMIN_BASE } from '../addresses'
import { expectNoAxeViolations } from '../test/axe'
import { dashboard, fakeApi, type Routes } from '../test/fakeApi'
import { renderApp } from '../test/renderApp'
import { LeftPane, NAV, type NavGroup } from './LeftPane'

const pane = () => screen.getByRole('navigation', { name: 'Navigation' })

/** The pane by itself, drawn from `nav` at /admin/, over the fake API */
async function renderPane(nav: readonly NavGroup[], routes: Routes = {}) {
  const rootRoute = createRootRoute({ component: () => <LeftPane nav={nav} /> })
  const router = createRouter({
    routeTree: rootRoute.addChildren([createRoute({ getParentRoute: () => rootRoute, path: '/' })]),
    basepath: ADMIN_BASE,
    history: createMemoryHistory({ initialEntries: [`${ADMIN_BASE}/`] }),
  })
  await router.load()
  const api = fakeApi(routes)
  const view = render(
    <ServicesProvider api={api} signedOut={createSignedOut()}>
      <QueryClientProvider client={createQueryClient({ retry: false })}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </ServicesProvider>,
  )
  return { api, view }
}

describe('The left pane', () => {
  it('shows only the screens that have shipped: the Dashboard, Clients, Matters and Workspace Templates under Firm, Users and Groups under People, Stores and Backends under Storage, and the Classic Interface at its foot', async () => {
    const { view } = await renderApp('/')
    const links = within(pane()).getAllByRole('link')
    expect(links.map((link) => [link.textContent, link.getAttribute('href')])).toEqual([
      ['Dashboard', '/admin/'],
      ['Clients', '/admin/clients'],
      ['Matters', '/admin/matters'],
      ['Workspace Templates', '/admin/templates'],
      ['Users', '/admin/users'],
      ['Groups', '/admin/groups'],
      ['Stores', '/admin/stores'],
      ['Backends', '/admin/backends'],
      ['Classic Interface', '/frontend.jsp'],
    ])
    // Only Firm, People and Storage have a shipped screen yet, so only their groups are drawn
    expect(within(pane()).getAllByRole('heading').map((heading) => heading.textContent)).toEqual(['Firm', 'People', 'Storage'])
    expect(within(pane()).getByRole('region', { name: 'Firm' })).toBeTruthy()
    expect(within(pane()).getByRole('region', { name: 'People' })).toBeTruthy()
    expect(within(pane()).getByRole('region', { name: 'Storage' })).toBeTruthy()
    await expectNoAxeViolations(view.container)
  })

  it('marks the screen that is open, and only that one', async () => {
    await renderApp('/')
    expect(within(pane()).getByRole('link', { name: 'Dashboard' }).getAttribute('aria-current')).toBe('page')
    expect(within(pane()).getByRole('link', { name: 'Classic Interface' }).getAttribute('aria-current')).toBeNull()
  })

  it('asks nothing for counts while no screen it shows has one', async () => {
    const uncounted = NAV.map((group) => ({ ...group, items: group.items.map((item) => ({ ...item, count: undefined })) }))
    const { api } = await renderPane(uncounted)
    expect(within(pane()).getByRole('link', { name: 'Dashboard' })).toBeTruthy()
    expect(api.callsTo('GET /v1/admin/dashboard')).toEqual([])
  })

  it('lists its groups in order, each with its heading, leaving out a group with no screen shipped', async () => {
    const nav: NavGroup[] = [
      { group: null, items: [{ label: 'admin.dashboard.title', route: '/', icon: 'dashboard' }] },
      { group: 'admin.nav.firm', items: [] },
      { group: 'admin.nav.monitoring', items: [{ label: 'admin.dashboard.title', route: '/', icon: 'dashboard' }] },
    ]
    const { view } = await renderPane(nav)
    expect(within(pane()).getAllByRole('heading').map((heading) => heading.textContent)).toEqual(['Monitoring'])
    expect(within(pane()).queryByText('Firm')).toBeNull()
    await expectNoAxeViolations(view.container)
  })

  it('shows a count beside a screen where something needs attention: the counts of its attention codes, added up', async () => {
    const nav: NavGroup[] = [{ group: 'admin.nav.monitoring', items: [{ label: 'admin.dashboard.title', route: '/', icon: 'dashboard', count: ['errors', 'index-failed', 'disk-low'] }] }]
    const { api, view } = await renderPane(nav, { 'GET /v1/admin/dashboard': dashboard })
    // 14 errors and 2 documents that couldn't be indexed; no disk is low
    const entry = await within(pane()).findByRole('link', { name: 'Dashboard, 16 need attention' })
    expect(entry.querySelector('[data-count]')?.textContent).toBe('16')
    expect(entry.querySelector('[data-count]')?.getAttribute('aria-hidden')).toBe('true')
    expect(api.callsTo('GET /v1/admin/dashboard')).toHaveLength(1)
    await expectNoAxeViolations(view.container)
  })

  it("shows Backends' count on another screen, from the Dashboard's answer, asked as the frame loads and at a return to the window but on no timer of its own", async () => {
    // Only the polls' intervals are moved on by hand; the app's other waits run as they do
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
    const attention = [...dashboard.attention, { code: 'backend-unreachable' as const, count: 2, detail: 'Wasabi EU, Office NAS', link: '/admin/backends' }]
    const { api } = await renderApp('/clients', { 'GET /v1/admin/dashboard': { ...dashboard, attention } })
    expect(await within(pane()).findByRole('link', { name: 'Backends, 2 need attention' })).toBeTruthy()
    expect(api.callsTo('GET /v1/admin/dashboard')).toHaveLength(1)
    // Only the Dashboard and running jobs ask every 30 seconds (spec 7.2)
    await act(async () => void vi.advanceTimersByTime(120_000))
    expect(api.callsTo('GET /v1/admin/dashboard')).toHaveLength(1)
    // A return to the window asks again, however fresh the answer
    await act(async () => void window.dispatchEvent(new Event('focus')))
    await waitFor(() => expect(api.callsTo('GET /v1/admin/dashboard')).toHaveLength(2))
  })

  it('shows no count when none of its codes needs attention', async () => {
    const nav: NavGroup[] = [{ group: null, items: [{ label: 'admin.dashboard.title', route: '/', icon: 'dashboard', count: ['disk-low'] }] }]
    let answered = false
    const { api } = await renderPane(nav, {
      'GET /v1/admin/dashboard': () => {
        answered = true
        return dashboard
      },
    })
    await waitFor(() => expect(answered).toBe(true))
    // A moment for the answer to be drawn
    await act(() => new Promise((resolve) => setTimeout(resolve, 20)))
    expect(api.callsTo('GET /v1/admin/dashboard')).toHaveLength(1)
    expect(within(pane()).getByRole('link', { name: 'Dashboard' })).toBeTruthy()
    expect(pane().querySelector('[data-count]')).toBeNull()
  })
})
