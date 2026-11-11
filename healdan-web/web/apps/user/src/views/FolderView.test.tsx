// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { act, fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import type { components } from '@healdan/api'
import { expectNoAxeViolations } from '../test/axe'
import { children, document, favorite, folder, page, problem, type Routes } from '../test/fakeApi'
import { bringObservedIntoView } from '../test/intersection'
import { renderApp } from '../test/renderApp'
import viewsCss from './views.module.css?raw'

type S = components['schemas']

const store: S['FolderRef'] = { id: 2, name: 'Smoke', kind: 'store' }
const workspace: S['FolderRef'] = { id: 110, name: '1001.1 – Charter Dispute', kind: 'matter' }

const correspondence = folder({
  id: 7,
  name: 'Correspondence',
  path: [store, workspace, { id: 7, name: 'Correspondence', kind: 'folder' }],
})

const drafts = folder({ id: 8, name: 'Drafts', path: [...correspondence.path, { id: 8, name: 'Drafts', kind: 'folder' }] })

function letters(count: number): S['DocumentView'][] {
  return Array.from({ length: count }, (_, i) =>
    document({ docNumber: 10400 + i, name: `Letter ${String(i + 1).padStart(3, '0')}.docx`, folder: { id: 7, name: 'Correspondence', kind: 'folder' } }),
  )
}

const seven = [
  document({ docNumber: 10388, name: 'Letter from Dunmere Pryce.pdf', fileVersion: '1.0', version: '1.0' }),
  document({
    docNumber: 10431,
    name: 'Letter to Dunmere Pryce.docx',
    fileVersion: '3.0',
    version: '3.1',
    author: { username: 'pnair', displayName: 'Priya Nair' },
    lastModified: '2026-10-03T09:42:00Z',
  }),
  ...letters(5),
]

function routes(over: Routes = {}): Routes {
  return {
    'GET /v1/folders/7': correspondence,
    'GET /v1/folders/7/children': children([drafts, ...seven]),
    ...over,
  }
}

const main = () => screen.getByRole('main')
const list = () => within(main()).getByRole('grid')
const dataRows = () => within(list()).getAllByRole('row').slice(1)
// The list shows once the folder has answered
const findRow = async (name: RegExp) => within(await within(main()).findByRole('grid')).findByRole('row', { name })

describe('FolderView', () => {
  it('shows the breadcrumb, the name with its star, the count, and the list with subfolders first', async () => {
    const { api } = await renderApp('/folders/7', routes())

    const crumbs = await within(main()).findByRole('navigation', { name: 'Breadcrumb' })
    expect(within(crumbs).getAllByRole('listitem').map((item) => item.textContent)).toEqual([
      'Saltmarsh Shipping Ltd',
      '1001.1 – Charter Dispute',
      'Correspondence',
    ])
    expect(within(crumbs).getByRole('link', { name: '1001.1 – Charter Dispute' }).getAttribute('href')).toBe('/app/folders/110')
    expect(within(crumbs).getByText('Correspondence').getAttribute('aria-current')).toBe('page')

    expect(within(main()).getByRole('heading', { level: 1, name: 'Correspondence' })).toBeTruthy()
    expect(await within(main()).findByRole('button', { name: 'Add to Favorites' })).toBeTruthy()
    expect(await within(main()).findByText('7 documents')).toBeTruthy()

    await findRow(/Drafts/)
    expect(within(list()).getAllByRole('columnheader').map((h) => h.textContent)).toEqual(['Title', 'Doc #', 'Version', 'Author', 'Edit Date'])
    const rows = dataRows()
    expect(rows).toHaveLength(8)
    expect(within(rows[0]!).getByRole('rowheader').textContent).toBe('Drafts')
    expect(rows[0]!.querySelector('.tabler-icon-folder')).toBeTruthy()
    const cells = within(rows[2]!).getAllByRole('gridcell').map((c) => c.textContent)
    expect(within(rows[2]!).getByRole('rowheader').textContent).toBe('Letter to Dunmere Pryce.docx')
    // Doc #, the file's version in Healdan's numbering, the Author's name and the edit date
    expect(cells).toEqual(['10431', '3', 'Priya Nair', 'Oct 3, 2026'])

    expect(api.callsTo('GET /v1/folders/7/children')).toEqual([
      { method: 'GET', path: '/v1/folders/7/children', query: { offset: 0, limit: 100 } },
    ])
  })

  it('sorts by a header, asking the server, and reverses on a second click', async () => {
    const { api, router } = await renderApp('/folders/7', routes())
    await findRow(/Drafts/)
    // The server's own order is by title
    expect(within(list()).getByRole('columnheader', { name: /Title/ }).getAttribute('aria-sort')).toBe('ascending')

    fireEvent.click(within(list()).getByRole('columnheader', { name: /Doc #/ }))
    await waitFor(() => expect(router.history.location.search).toBe('?sort=docNumber&order=asc'))
    await waitFor(() =>
      expect(api.callsTo('GET /v1/folders/7/children').at(-1)?.query).toEqual({ sort: 'docNumber', order: 'asc', offset: 0, limit: 100 }),
    )
    await waitFor(() => expect(within(list()).getByRole('columnheader', { name: /Doc #/ }).getAttribute('aria-sort')).toBe('ascending'))

    fireEvent.click(within(list()).getByRole('columnheader', { name: /Doc #/ }))
    await waitFor(() =>
      expect(api.callsTo('GET /v1/folders/7/children').at(-1)?.query).toEqual({ sort: 'docNumber', order: 'desc', offset: 0, limit: 100 }),
    )
    expect(router.history.location.search).toBe('?sort=docNumber&order=desc')
  })

  it('keeps the rows and the count while a new order loads, marking the list busy', async () => {
    let release = () => {}
    await renderApp(
      '/folders/7',
      routes({
        'GET /v1/folders/7/children': ({ query }: { query?: Record<string, unknown> }) => {
          const answer = children([drafts, ...[...seven].reverse()])
          return query?.sort ? new Promise((resolve) => (release = () => resolve(answer))) : children([drafts, ...seven])
        },
      }),
    )
    await findRow(/Drafts/)
    fireEvent.click(within(list()).getByRole('columnheader', { name: /Doc #/ }))
    await waitFor(() => expect(list().closest('[aria-busy="true"]')).toBeTruthy())
    expect(within(list()).getByRole('row', { name: /Letter from Dunmere Pryce/ })).toBeTruthy()
    expect(within(main()).getByText('7 documents')).toBeTruthy()
    act(() => release())
    await waitFor(() => expect(list().closest('[aria-busy="true"]')).toBeNull())
  })

  it('keeps a sort from the address', async () => {
    const { api } = await renderApp('/folders/7?sort=edited&order=desc', routes())
    await findRow(/Drafts/)
    expect(within(list()).getByRole('columnheader', { name: /Edit Date/ }).getAttribute('aria-sort')).toBe('descending')
    expect(api.callsTo('GET /v1/folders/7/children')[0]?.query).toEqual({ sort: 'edited', order: 'desc', offset: 0, limit: 100 })
  })

  it('asks for the next 100 rows once as the end comes into view, and the count stays the total', async () => {
    const all = letters(250)
    const { api } = await renderApp(
      '/folders/7',
      routes({
        'GET /v1/folders/7/children': ({ query }: { query?: Record<string, unknown> }) => {
          const offset = Number(query?.offset)
          return children(all.slice(offset, offset + Number(query?.limit)), { offset, total: 250, documents: 250 })
        },
      }),
    )
    await findRow(/Letter 001/)
    expect(dataRows()).toHaveLength(100)
    expect(within(main()).getByText('250 documents')).toBeTruthy()

    act(() => {
      bringObservedIntoView()
      bringObservedIntoView()
    })
    await findRow(/Letter 200/)
    expect(api.callsTo('GET /v1/folders/7/children').map((call) => call.query?.offset)).toEqual([0, 100])
    expect(dataRows()).toHaveLength(200)
    expect(within(main()).getByText('250 documents')).toBeTruthy()
  })

  it('puts the selected document in the address', async () => {
    const { router } = await renderApp('/folders/7', routes())
    const row = await findRow(/Letter from Dunmere Pryce/)
    fireEvent.click(within(row).getByRole('rowheader'))
    await waitFor(() => expect(router.history.location.search).toBe('?sel=10388'))
    expect(row.getAttribute('aria-selected')).toBe('true')
  })

  it('keeps several selected documents in the view, out of the address, which holds one', async () => {
    const user = userEvent.setup()
    const { router } = await renderApp('/folders/7?sel=10388', routes())
    const first = await findRow(/Letter from Dunmere Pryce/)
    const second = within(list()).getByRole('row', { name: /Letter to Dunmere Pryce/ })
    await user.keyboard('{Shift>}')
    await user.click(within(second).getByRole('rowheader'))
    await user.keyboard('{/Shift}')
    await waitFor(() => expect(router.history.location.search).toBe(''))
    expect(first.getAttribute('aria-selected')).toBe('true')
    expect(second.getAttribute('aria-selected')).toBe('true')

    await user.click(within(second).getByRole('rowheader'))
    await waitFor(() => expect(router.history.location.search).toBe('?sel=10431'))
    expect(first.getAttribute('aria-selected')).toBe('false')
  })

  it('selects the document an address names', async () => {
    await renderApp('/folders/7?sel=10431', routes())
    const named = await findRow(/Letter to Dunmere Pryce/)
    expect(named.getAttribute('aria-selected')).toBe('true')
    expect(within(list()).getByRole('row', { name: /Letter from Dunmere Pryce/ }).getAttribute('aria-selected')).toBe('false')
  })

  it('opens a subfolder with Enter, and selects a document with it', async () => {
    const { router } = await renderApp('/folders/7', routes({ 'GET /v1/folders/8': drafts, 'GET /v1/folders/8/children': children([]) }))
    const doc = await findRow(/Letter to Dunmere Pryce/)
    act(() => doc.focus())
    fireEvent.keyDown(doc, { key: 'Enter' })
    fireEvent.keyUp(doc, { key: 'Enter' })
    await waitFor(() => expect(router.history.location.search).toBe('?sel=10431'))

    const sub = within(list()).getByRole('row', { name: /Drafts/ })
    act(() => sub.focus())
    fireEvent.keyDown(sub, { key: 'Enter' })
    fireEvent.keyUp(sub, { key: 'Enter' })
    await waitFor(() => expect(router.history.location.pathname).toBe('/app/folders/8'))
    expect(await within(main()).findByText('This folder is empty.')).toBeTruthy()
  })

  it('adds the folder to Favorites with the star, and takes it out again', async () => {
    let favorites: S['FavoriteView'][] = []
    const { api } = await renderApp(
      '/folders/7',
      routes({
        'GET /v1/me/favorites': () => page(favorites),
        'POST /v1/me/favorites': () => {
          favorites = [favorite(correspondence, 950)]
          return favorites[0]
        },
        'DELETE /v1/me/favorites/*': () => {
          favorites = []
          return undefined
        },
      }),
    )
    fireEvent.click(await within(main()).findByRole('button', { name: 'Add to Favorites' }))
    const remove = await within(main()).findByRole('button', { name: 'Remove from Favorites' })
    expect(api.callsTo('POST /v1/me/favorites')).toHaveLength(1)
    // The pane's list shows it too
    expect(await within(screen.getByRole('navigation', { name: 'Navigation' })).findByRole('link', { name: /Correspondence/ })).toBeTruthy()

    fireEvent.click(remove)
    expect(await within(main()).findByRole('button', { name: 'Add to Favorites' })).toBeTruthy()
    expect(api.callsTo('DELETE /v1/me/favorites/950')).toHaveLength(1)
  })

  it('fills a favorite\'s star with the helper\'s amber, edged so it shows on a light page', async () => {
    await renderApp('/folders/7', routes({ 'GET /v1/me/favorites': page([favorite(correspondence, 950)]) }))
    const star = await within(main()).findByRole('button', { name: 'Remove from Favorites' })
    expect(star.querySelector('.starred .tabler-icon-star-filled')).toBeTruthy()
    // jsdom draws nothing, so the rule that edges it is read instead
    expect(viewsCss).toMatch(/\.starred svg\s*\{[^}]*stroke:\s*var\(--hd-star-edge\)/)
  })

  it('says so when a favorite can\'t be changed', async () => {
    await renderApp('/folders/7', routes({ 'GET /v1/me/favorites': page([]), 'POST /v1/me/favorites': problem(500, 'server-error') }))
    fireEvent.click(await within(main()).findByRole('button', { name: 'Add to Favorites' }))
    expect(await screen.findByText('Something went wrong.')).toBeTruthy()
  })

  it('shows a matter\'s top level: its folders and search folders, and its documents if any', async () => {
    const top = folder({ id: 110, name: '1001.1 – Charter Dispute', kind: 'matter', canFile: false, path: [store, workspace] })
    const search = folder({ id: 112, name: 'My Recent Docs Here', kind: 'searchFolder', path: [store, workspace, { id: 112, name: 'My Recent Docs Here', kind: 'searchFolder' }] })
    await renderApp(
      '/folders/110',
      routes({
        'GET /v1/folders/110': top,
        'GET /v1/folders/110/children': ({ query }: { query?: Record<string, unknown> }) =>
          query?.type === 'folder' ? children([correspondence, search]) : children([correspondence, search, document({ docNumber: 10002, name: 'Engagement Letter.pdf' })]),
      }),
    )
    const crumbs = await within(main()).findByRole('navigation', { name: 'Breadcrumb' })
    expect(within(crumbs).getAllByRole('listitem').map((item) => item.textContent)).toEqual(['Saltmarsh Shipping Ltd', '1001.1 – Charter Dispute'])
    expect(within(main()).getByRole('heading', { level: 1, name: '1001.1 – Charter Dispute' })).toBeTruthy()
    // A matter goes in My Matters, not Favorites
    expect(within(main()).queryByRole('button', { name: /Favorites/ })).toBeNull()

    await findRow(/Engagement Letter/)
    expect(dataRows().map((row) => within(row).getByRole('rowheader').textContent)).toEqual(['Correspondence', 'My Recent Docs Here', 'Engagement Letter.pdf'])
    expect(dataRows()[1]!.querySelector('.tabler-icon-folder-search')).toBeTruthy()
    expect(within(main()).getByText('1 document')).toBeTruthy()
  })

  it('lists a search folder in its own order until a header is clicked', async () => {
    const recent = folder({ id: 112, name: 'My Recent Docs Here', kind: 'searchFolder', path: [store, workspace, { id: 112, name: 'My Recent Docs Here', kind: 'searchFolder' }] })
    await renderApp('/folders/112', routes({ 'GET /v1/folders/112': recent, 'GET /v1/folders/112/children': children(seven) }))
    await findRow(/Letter from Dunmere Pryce/)
    for (const header of within(list()).getAllByRole('columnheader')) expect(header.getAttribute('aria-sort')).not.toBe('ascending')
  })

  it('says a Store can\'t be opened as a folder', async () => {
    await renderApp(
      '/folders/2',
      routes({
        'GET /v1/folders/2': folder({ id: 2, name: 'Smoke', kind: 'store', matter: null, path: [store] }),
        'GET /v1/folders/2/children': problem(409, 'not-browsable'),
      }),
    )
    expect(await within(main()).findByText("That can't be opened as a folder.")).toBeTruthy()
    expect(within(main()).queryByRole('grid')).toBeNull()
    expect(within(main()).queryByRole('button', { name: /Favorites/ })).toBeNull()
  })

  it('says a folder that isn\'t there, or can\'t be read, is not found', async () => {
    await renderApp('/folders/99', routes())
    expect(await within(main()).findByText("Not found, or you can't see it.")).toBeTruthy()
    expect(within(main()).queryByRole('grid')).toBeNull()
  })

  it('shows a list that failed with Try Again, which asks again', async () => {
    let fail = true
    const { api } = await renderApp(
      '/folders/7',
      routes({ 'GET /v1/folders/7/children': () => (fail ? problem(500, 'server-error') : children(seven)) }),
    )
    const retry = await within(main()).findByRole('button', { name: 'Try Again' })
    fail = false
    fireEvent.click(retry)
    await findRow(/Letter from Dunmere Pryce/)
    expect(api.callsTo('GET /v1/folders/7/children')).toHaveLength(2)
  })

  it('passes axe', async () => {
    await renderApp('/folders/7?sel=10388', routes())
    await findRow(/Drafts/)
    await expectNoAxeViolations(main())
  })
})
