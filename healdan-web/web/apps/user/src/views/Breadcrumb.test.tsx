// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { components } from '@healdan/api'
import { children, client, folder } from '../test/fakeApi'
import { renderApp } from '../test/renderApp'

type S = components['schemas']

const store: S['FolderRef'] = { id: 2, name: 'Smoke', kind: 'store' }
const workspace: S['FolderRef'] = { id: 110, name: '1001.1 – Charter Dispute', kind: 'matter' }
const correspondence: S['FolderRef'] = { id: 7, name: 'Correspondence', kind: 'folder' }

async function crumbsAt(view: S['FolderView']) {
  await renderApp(`/folders/${view.id}`, { [`GET /v1/folders/${view.id}`]: view })
  return within(await screen.findByRole('navigation', { name: 'Breadcrumb' }))
}

describe('Breadcrumb', () => {
  it('runs client › matter › each folder down to this one, leaving out the Store', async () => {
    const crumbs = await crumbsAt(
      folder({ id: 8, name: 'Drafts <b>', path: [store, workspace, correspondence, { id: 8, name: 'Drafts <b>', kind: 'folder' }] }),
    )
    expect(crumbs.getAllByRole('listitem').map((item) => item.textContent)).toEqual([
      'Saltmarsh Shipping Ltd',
      '1001.1 – Charter Dispute',
      'Correspondence',
      'Drafts <b>',
    ])
    expect(crumbs.getAllByRole('link').map((link) => [link.textContent, link.getAttribute('href')])).toEqual([
      ['1001.1 – Charter Dispute', '/app/folders/110'],
      ['Correspondence', '/app/folders/7'],
    ])
    // The folder you're in is plain text, marked as the current page; its name is text, never markup
    const here = crumbs.getByText('Drafts <b>')
    expect(here.tagName).not.toBe('A')
    expect(here.getAttribute('aria-current')).toBe('page')
  })

  it('names the matter as the API does, client.matter – name, with the client before it', async () => {
    const crumbs = await crumbsAt(
      folder({
        id: 120,
        name: '1002.1 – Commercial Lease',
        kind: 'matter',
        matter: { id: 12, display: '1002.1 – Commercial Lease', client: client({ id: 2, number: 1002, name: 'Brightwell' }) },
        path: [store, { id: 120, name: '1002.1 – Commercial Lease', kind: 'matter' }],
      }),
    )
    expect(crumbs.getAllByRole('listitem').map((item) => item.textContent)).toEqual(['Brightwell', '1002.1 – Commercial Lease'])
    expect(crumbs.queryAllByRole('link')).toHaveLength(0)
  })

  it('has nothing to show for a Store, which is no client or matter', async () => {
    await renderApp('/folders/2', {
      'GET /v1/folders/2': folder({ id: 2, name: 'Smoke', kind: 'store', matter: null, path: [store] }),
      'GET /v1/folders/2/children': children([]),
    })
    expect(await screen.findByRole('heading', { level: 1, name: 'Smoke' })).toBeTruthy()
    expect(screen.queryByRole('navigation', { name: 'Breadcrumb' })).toBeNull()
  })
})
