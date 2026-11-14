// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { components } from '@healdan/api'
import { setLabels } from '@healdan/ui'
import { expectNoAxeViolations } from '../test/axe'
import { comeBack, settle } from '../test/comeBack'
import { document as documentView, favorite, folder, matter, page, problem } from '../test/fakeApi'
import { renderApp } from '../test/renderApp'

type S = components['schemas']

// Correspondence, inside matter 1001.1, whose workspace is folder 110
const correspondence = folder({
  id: 111,
  path: [
    { id: 2, name: 'Smoke', kind: 'store' },
    { id: 110, name: '1001.1 – Charter Dispute', kind: 'matter' },
    { id: 111, name: 'Correspondence', kind: 'folder' },
  ],
})

const pane = () => screen.getByRole('navigation', { name: 'Navigation' })

describe('LeftPane', () => {
  it('lists Recent: Documents, Emails, Folders and Clients, each a link to its own address', async () => {
    await renderApp('/folders/1')
    const recent = Object.fromEntries(
      ['Documents', 'Emails', 'Folders', 'Clients'].map((name) => [name, within(pane()).getByRole('link', { name }).getAttribute('href')]),
    )
    expect(recent).toEqual({
      Documents: '/app/recent/documents',
      Emails: '/app/recent/emails',
      Folders: '/app/recent/folders',
      Clients: '/app/recent/clients',
    })
    // Each entry's icon is in its kind's colour, but Documents', which takes in every kind
    const kinds = Object.fromEntries(
      ['Documents', 'Emails', 'Folders', 'Clients'].map((name) => [name, within(pane()).getByRole('link', { name }).querySelector('svg')!.getAttribute('data-kind')]),
    )
    expect(kinds).toEqual({ Documents: null, Emails: 'email', Folders: 'folder', Clients: 'client' })
  })

  it('shows Recent Matters with the client apart from the number and name', async () => {
    await renderApp('/folders/1')
    const entry = await within(pane()).findByRole('link', { name: /1002\.1/ })
    expect(entry.getAttribute('href')).toBe('/app/folders/120')
    expect(entry.textContent).toBe('1002.1 – Commercial Lease Brightwell')
    const client = within(entry).getByText('Brightwell')
    expect(client.className).toContain('client')
    // The matter's briefcase, whole however long the entry, in the matters' colour
    const icon = entry.querySelector('svg')!
    expect(icon.getAttribute('data-kind')).toBe('matter')
    expect(getComputedStyle(icon).flexShrink).toBe('0')
  })

  it('shows My Matters as a tree: a matter, and when opened its folders and search folders', async () => {
    const { api } = await renderApp('/folders/1')
    const tree = await screen.findByRole('treegrid', { name: 'My Matters' })
    expect(within(tree).getByRole('row', { name: /1001\.1/ })).toBeTruthy()
    expect(within(tree).getByText('Saltmarsh Shipping Ltd')).toBeTruthy()
    fireEvent.click(within(tree).getByRole('button', { name: /expand/i }))
    const folder = await within(tree).findByRole('row', { name: /Correspondence/ })
    const search = within(tree).getByRole('row', { name: /My Recent Docs Here/ })
    expect(folder.querySelector('.tabler-icon-folder')!.getAttribute('data-kind')).toBe('folder')
    expect(search.querySelector('.tabler-icon-folder-search')!.getAttribute('data-kind')).toBe('folder')
    expect(within(tree).getByRole('row', { name: /1001\.1/ }).querySelector('.tabler-icon-briefcase')!.getAttribute('data-kind')).toBe('matter')
    expect(api.callsTo('GET /v1/folders/110/children')).toEqual([
      { method: 'GET', path: '/v1/folders/110/children', query: { type: 'folder', limit: 200, offset: 0 } },
    ])
  })

  it('reads a matter with more folders than a page holds page by page', async () => {
    const many = Array.from({ length: 210 }, (_, i) => folder({ id: 1000 + i, name: `Folder ${String(i).padStart(3, '0')}` }))
    const { api } = await renderApp('/folders/1', {
      'GET /v1/folders/*/children': ({ query }: { query?: Record<string, unknown> }) => {
        const offset = Number(query?.offset ?? 0)
        const limit = Number(query?.limit ?? 50)
        return { items: many.slice(offset, offset + limit), offset, limit, total: many.length }
      },
    })
    const tree = await screen.findByRole('treegrid', { name: 'My Matters' })
    fireEvent.click(within(tree).getByRole('button', { name: /expand/i }))
    expect(await within(tree).findByRole('row', { name: /Folder 209/ })).toBeTruthy()
    expect(within(tree).getAllByRole('row', { name: /^Folder / })).toHaveLength(210)
    expect(api.callsTo('GET /v1/folders/110/children').map((c) => c.query?.offset)).toEqual([0, 200])
  })

  it('asks for a matter\'s children only once it is opened, and only once', async () => {
    const { api } = await renderApp('/folders/1')
    const tree = await screen.findByRole('treegrid', { name: 'My Matters' })
    expect(api.callsTo('GET /v1/folders/110/children')).toHaveLength(0)
    const chevron = within(tree).getByRole('button', { name: /expand/i })
    fireEvent.click(chevron)
    await within(tree).findByRole('row', { name: /Correspondence/ })
    fireEvent.click(within(tree).getByRole('button', { name: /collapse/i }))
    fireEvent.click(within(tree).getByRole('button', { name: /expand/i }))
    await within(tree).findByRole('row', { name: /Correspondence/ })
    expect(api.callsTo('GET /v1/folders/110/children')).toHaveLength(1)
  })

  it("asks again for the folder's list when the person comes back to the window, but not My Matters or an open matter's folders", async () => {
    const { api } = await renderApp('/folders/1')
    const tree = await screen.findByRole('treegrid', { name: 'My Matters' })
    fireEvent.click(within(tree).getByRole('button', { name: /expand/i }))
    await within(tree).findByRole('row', { name: /Correspondence/ })
    const listed = () => api.callsTo('GET /v1/folders/1/children').filter((call) => call.query?.type !== 'folder').length
    const before = listed()
    expect(before).toBeGreaterThan(0)
    const mine = api.callsTo('GET /v1/matters/mine').length

    comeBack()
    await waitFor(() => expect(listed()).toBe(before + 1))
    await settle()
    expect(api.callsTo('GET /v1/matters/mine')).toHaveLength(mine)
    expect(api.callsTo('GET /v1/folders/110/children')).toHaveLength(1)
  })

  it('opens a matter or a folder from the tree at its address', async () => {
    const { router } = await renderApp('/folders/1')
    const tree = await screen.findByRole('treegrid', { name: 'My Matters' })
    fireEvent.click(within(tree).getByRole('row', { name: /1001\.1/ }))
    await waitFor(() => expect(router.history.location.pathname).toBe('/app/folders/110'))
    fireEvent.click(within(tree).getByRole('button', { name: /expand/i }))
    fireEvent.click(await within(tree).findByRole('row', { name: /Correspondence/ }))
    await waitFor(() => expect(router.history.location.pathname).toBe('/app/folders/111'))
  })

  it('marks the open matter in the tree', async () => {
    await renderApp('/folders/110')
    const tree = await screen.findByRole('treegrid', { name: 'My Matters' })
    expect(within(tree).getByRole('row', { name: /1001\.1/ }).getAttribute('data-current')).toBe('true')
    expect(within(tree).getByRole('row', { name: /1001\.1/ }).getAttribute('aria-current')).toBe('page')
  })

  it('works from the keyboard: Right opens a matter (asking once), Enter opens a row\'s folder', async () => {
    const { api, router } = await renderApp('/folders/1')
    const tree = await screen.findByRole('treegrid', { name: 'My Matters' })
    const matterRow = within(tree).getByRole('row', { name: /1001\.1/ })
    matterRow.focus()
    expect(document.activeElement).toBe(matterRow)
    fireEvent.keyDown(matterRow, { key: 'ArrowRight' })
    const child = await within(tree).findByRole('row', { name: /Correspondence/ })
    fireEvent.keyDown(matterRow, { key: 'ArrowDown' })
    expect(document.activeElement).toBe(child)
    fireEvent.keyDown(child, { key: 'Enter' })
    fireEvent.keyUp(child, { key: 'Enter' })
    await waitFor(() => expect(router.history.location.pathname).toBe('/app/folders/111'))
    expect(api.callsTo('GET /v1/folders/110/children')).toHaveLength(1)
  })

  it('opens the tree to the folder that is open, and marks it', async () => {
    const { api } = await renderApp('/folders/111', { 'GET /v1/folders/111': correspondence })
    const tree = await screen.findByRole('treegrid', { name: 'My Matters' })
    const row = await within(tree).findByRole('row', { name: /Correspondence/ })
    expect(row.getAttribute('aria-current')).toBe('page')
    expect(row.getAttribute('data-current')).toBe('true')
    expect(within(tree).getByRole('row', { name: /1001\.1/ }).getAttribute('aria-expanded')).toBe('true')
    expect(api.callsTo('GET /v1/folders/110/children')).toHaveLength(1)
  })

  it('moves the mark to the row of the folder opened next', async () => {
    const { router } = await renderApp('/folders/110', { 'GET /v1/folders/111': correspondence })
    const tree = await screen.findByRole('treegrid', { name: 'My Matters' })
    const matterRow = within(tree).getByRole('row', { name: /1001\.1/ })
    expect(matterRow.getAttribute('aria-current')).toBe('page')
    fireEvent.click(within(tree).getByRole('button', { name: /expand/i }))
    fireEvent.click(await within(tree).findByRole('row', { name: /Correspondence/ }))
    await waitFor(() => expect(router.history.location.pathname).toBe('/app/folders/111'))
    await waitFor(() => expect(within(tree).getByRole('row', { name: /Correspondence/ }).getAttribute('aria-current')).toBe('page'))
    expect(within(tree).getByRole('row', { name: /Correspondence/ }).getAttribute('data-current')).toBe('true')
    expect(within(tree).getByRole('row', { name: /1001\.1/ }).getAttribute('aria-current')).toBeNull()
    expect(within(tree).getByRole('row', { name: /1001\.1/ }).getAttribute('data-current')).toBeNull()
  })

  it('adds the open matter to My Matters with +, and the tree shows it', async () => {
    let mine: S['MatterView'][] = []
    const { api } = await renderApp('/folders/111', {
      'GET /v1/folders/111': correspondence,
      'GET /v1/matters/mine': () => page(mine),
      'PUT /v1/me/matters/*': () => {
        mine = [matter()]
        return undefined
      },
    })
    fireEvent.click(await within(pane()).findByRole('button', { name: 'Add This Matter to My Matters' }))
    const tree = await within(pane()).findByRole('treegrid', { name: 'My Matters' })
    expect(within(tree).getByRole('row', { name: /1001\.1/ })).toBeTruthy()
    expect(api.callsTo('PUT /v1/me/matters/110')).toHaveLength(1)
    // Once it's there, + has nothing to add
    await waitFor(() => expect(within(pane()).queryByRole('button', { name: 'Add This Matter to My Matters' })).toBeNull())
  })

  it('offers + only inside a matter that isn\'t in My Matters yet', async () => {
    await renderApp('/folders/111', { 'GET /v1/folders/111': correspondence })
    await screen.findByRole('treegrid', { name: 'My Matters' })
    await within(pane()).findByRole('row', { name: /Correspondence/ })
    expect(within(pane()).queryByRole('button', { name: 'Add This Matter to My Matters' })).toBeNull()
  })

  it('offers no + outside a matter', async () => {
    await renderApp('/recent/documents', { 'GET /v1/matters/mine': page([]) })
    await waitFor(() => expect(within(pane()).getAllByText('None yet.').length).toBeGreaterThan(0))
    expect(within(pane()).queryByRole('button', { name: 'Add This Matter to My Matters' })).toBeNull()
  })

  it('takes a matter out of My Matters from its menu', async () => {
    let mine = [matter()]
    const { api } = await renderApp('/folders/1', {
      'GET /v1/matters/mine': () => page(mine),
      'DELETE /v1/me/matters/*': () => {
        mine = []
        return undefined
      },
    })
    const tree = await screen.findByRole('treegrid', { name: 'My Matters' })
    fireEvent.click(within(tree).getByRole('button', { name: 'More for 1001.1 – Charter Dispute' }))
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Remove from My Matters' }))
    await waitFor(() => expect(within(pane()).queryByRole('treegrid', { name: 'My Matters' })).toBeNull())
    expect(api.callsTo('DELETE /v1/me/matters/110')).toHaveLength(1)
  })

  it('asks for Recent Clients again when My Matters changes, as its clients come from both', async () => {
    let mine = [matter()]
    const { api } = await renderApp('/recent/clients', {
      'GET /v1/matters/mine': () => page(mine),
      'DELETE /v1/me/matters/*': () => {
        mine = []
        return undefined
      },
    })
    await screen.findByRole('heading', { level: 1, name: 'Recent Clients' })
    await waitFor(() => expect(api.callsTo('GET /v1/clients/recent')).toHaveLength(1))
    const tree = await screen.findByRole('treegrid', { name: 'My Matters' })
    fireEvent.click(within(tree).getByRole('button', { name: 'More for 1001.1 – Charter Dispute' }))
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Remove from My Matters' }))
    await waitFor(() => expect(api.callsTo('GET /v1/clients/recent')).toHaveLength(2))
  })

  it('says Loading… while a section\'s list is on its way, never an empty list', async () => {
    const waiting = () => new Promise(() => {})
    await renderApp('/folders/1', { 'GET /v1/matters/mine': waiting, 'GET /v1/matters/recent': waiting, 'GET /v1/me/favorites': waiting })
    expect(within(pane()).getAllByText('Loading…')).toHaveLength(3)
    expect(within(pane()).queryByRole('treegrid')).toBeNull()
    expect(within(pane()).getAllByRole('list')).toHaveLength(1)
    await expectNoAxeViolations(pane())
  })

  it('shows My Favorites: documents and folders', async () => {
    await renderApp('/folders/1')
    const doc = await within(pane()).findByRole('link', { name: /Cap Table/ })
    expect(doc.getAttribute('href')).toBe('/app/doc/10500')
    expect(within(pane()).getByRole('link', { name: /Due Diligence/ }).getAttribute('href')).toBe('/app/folders/130')
  })

  it('draws each favourite with the icon and colour of its kind, as the lists do, not a star', async () => {
    await renderApp('/folders/1', {
      'GET /v1/me/favorites': page([
        favorite(documentView({ docNumber: 10500, name: 'Cap Table.xlsx' }), 901),
        favorite(documentView({ docNumber: 10501, name: 'Lease.odt' }), 902),
        favorite(documentView({ docNumber: 10502, name: 'Site Plan.odg' }), 903),
        favorite(folder({ id: 130, name: 'Due Diligence' }), 904),
        favorite(folder({ id: 131, name: 'My Recent Docs', kind: 'searchFolder' }), 905),
        favorite(folder({ id: 132, name: '1004.1 – Harbour Lease', kind: 'matter' }), 906),
      ]),
    })
    const favorites = within(pane()).getByRole('region', { name: 'My Favorites' })
    await within(favorites).findByRole('link', { name: /Cap Table/ })
    const icon = (name: RegExp) => {
      const svg = within(favorites).getByRole('link', { name }).querySelector('svg')!
      return [/tabler-icon-([\w-]+)/.exec(svg.getAttribute('class')!)![1], svg.getAttribute('data-kind')]
    }
    expect(icon(/Cap Table/)).toEqual(['file-type-xls', 'spreadsheet'])
    expect(icon(/Lease\.odt/)).toEqual(['file-text', 'wordProcessing'])
    expect(icon(/Site Plan/)).toEqual(['file-text', null])
    expect(icon(/Due Diligence/)).toEqual(['folder', 'folder'])
    expect(icon(/My Recent Docs/)).toEqual(['folder-search', 'folder'])
    expect(icon(/1004\.1/)).toEqual(['briefcase', 'matter'])
    expect(favorites.querySelector('.tabler-icon-star')).toBeNull()
    // The heading is the section's own, as before
    expect(within(favorites).getByRole('heading', { name: 'My Favorites' })).toBeTruthy()
  })

  it('uses the firm\'s word for a matter', async () => {
    setLabels({ matter: 'Project', matters: 'Projects' })
    await renderApp('/folders/1')
    expect(within(pane()).getByRole('heading', { name: 'Recent Projects' })).toBeTruthy()
    expect(within(pane()).getByRole('heading', { name: 'My Projects' })).toBeTruthy()
    expect(await screen.findByRole('treegrid', { name: 'My Projects' })).toBeTruthy()
  })

  it('says None yet for an empty section, and shows a section\'s failure with Try Again', async () => {
    await renderApp('/folders/1', {
      'GET /v1/matters/recent': { items: [], offset: 0, limit: 0, total: 0 },
      'GET /v1/me/favorites': problem(500, 'server-error'),
    })
    await waitFor(() => expect(within(pane()).getAllByText('None yet.').length).toBeGreaterThan(0))
    expect(await within(pane()).findByRole('button', { name: 'Try Again' })).toBeTruthy()
    expect(document.body.textContent).not.toContain('server sentence')
  })

  it('passes axe, with a matter open', async () => {
    await renderApp('/folders/1')
    const tree = await screen.findByRole('treegrid', { name: 'My Matters' })
    fireEvent.click(within(tree).getByRole('button', { name: /expand/i }))
    await within(tree).findByRole('row', { name: /Correspondence/ })
    await expectNoAxeViolations(pane())
  })
})
