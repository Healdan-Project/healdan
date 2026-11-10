// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { setLabels } from '@healdan/ui'
import { expectNoAxeViolations } from '../test/axe'
import { problem } from '../test/fakeApi'
import { renderApp } from '../test/renderApp'

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
  })

  it('shows Recent Matters with the client apart from the number and name', async () => {
    await renderApp('/folders/1')
    const entry = await within(pane()).findByRole('link', { name: /1002\.1/ })
    expect(entry.getAttribute('href')).toBe('/app/folders/120')
    expect(entry.textContent).toBe('1002.1 – Commercial Lease Brightwell')
    const client = within(entry).getByText('Brightwell')
    expect(client.className).toContain('client')
  })

  it('shows My Matters as a tree: a matter, and when opened its folders and search folders', async () => {
    const { api } = await renderApp('/folders/1')
    const tree = await screen.findByRole('treegrid', { name: 'My Matters' })
    expect(within(tree).getByRole('row', { name: /1001\.1/ })).toBeTruthy()
    expect(within(tree).getByText('Saltmarsh Shipping Ltd')).toBeTruthy()
    fireEvent.click(within(tree).getByRole('button', { name: /expand/i }))
    const folder = await within(tree).findByRole('row', { name: /Correspondence/ })
    const search = within(tree).getByRole('row', { name: /My Recent Docs Here/ })
    expect(folder.querySelector('.tabler-icon-folder')).toBeTruthy()
    expect(search.querySelector('.tabler-icon-folder-search')).toBeTruthy()
    expect(api.callsTo('GET /v1/folders/110/children')).toEqual([
      { method: 'GET', path: '/v1/folders/110/children', query: { type: 'folder' } },
    ])
  })

  it('asks for a matter\'s children only once it is opened, and only once', async () => {
    const { api } = await renderApp('/folders/1')
    const tree = await screen.findByRole('treegrid', { name: 'My Matters' })
    expect(api.callsTo('GET /v1/folders/*/children')).toHaveLength(0)
    const chevron = within(tree).getByRole('button', { name: /expand/i })
    fireEvent.click(chevron)
    await within(tree).findByRole('row', { name: /Correspondence/ })
    fireEvent.click(within(tree).getByRole('button', { name: /collapse/i }))
    fireEvent.click(within(tree).getByRole('button', { name: /expand/i }))
    await within(tree).findByRole('row', { name: /Correspondence/ })
    expect(api.callsTo('GET /v1/folders/*/children')).toHaveLength(1)
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
  })

  it('shows My Favorites: documents and folders', async () => {
    await renderApp('/folders/1')
    const doc = await within(pane()).findByRole('link', { name: /Cap Table/ })
    expect(doc.getAttribute('href')).toBe('/app/doc/10500')
    expect(within(pane()).getByRole('link', { name: /Due Diligence/ }).getAttribute('href')).toBe('/app/folders/130')
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
