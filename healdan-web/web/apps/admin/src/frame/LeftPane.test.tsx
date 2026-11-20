// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { QueryClientProvider } from '@tanstack/react-query'
import { createMemoryHistory, createRootRoute, createRoute, createRouter, RouterProvider } from '@tanstack/react-router'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
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
  it('shows only the screens that have shipped: the Dashboard, Clients, Matters and Workspace Templates under Firm, and the Classic Interface at its foot', async () => {
    const { view } = await renderApp('/')
    const links = within(pane()).getAllByRole('link')
    expect(links.map((link) => [link.textContent, link.getAttribute('href')])).toEqual([
      ['Dashboard', '/admin/'],
      ['Clients', '/admin/clients'],
      ['Matters', '/admin/matters'],
      ['Workspace Templates', '/admin/templates'],
      ['Classic Interface', '/frontend.jsp'],
    ])
    // Only Firm has a shipped screen yet, so only its group is drawn
    expect(within(pane()).getAllByRole('heading').map((heading) => heading.textContent)).toEqual(['Firm'])
    expect(within(pane()).getByRole('region', { name: 'Firm' })).toBeTruthy()
    await expectNoAxeViolations(view.container)
  })

  it('marks the screen that is open, and only that one', async () => {
    await renderApp('/')
    expect(within(pane()).getByRole('link', { name: 'Dashboard' }).getAttribute('aria-current')).toBe('page')
    expect(within(pane()).getByRole('link', { name: 'Classic Interface' }).getAttribute('aria-current')).toBeNull()
  })

  it('asks nothing for counts while no screen it shows has one', async () => {
    const { api } = await renderPane(NAV)
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
