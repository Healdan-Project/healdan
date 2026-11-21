// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { act, fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, onTestFinished, vi } from 'vitest'
import type { components } from '@healdan/api'
import { expectNoAxeViolations } from '../test/axe'
import { resizeByKeys, sliderOf } from '../test/columns'
import { comeBack, settle } from '../test/comeBack'
import { client, document, folder, matter, problem, zone, type Routes } from '../test/fakeApi'
import { detailsRoutes } from '../test/details'
import { bringObservedIntoView } from '../test/intersection'
import { renderApp } from '../test/renderApp'

type S = components['schemas']

const brightwell = client({ id: 2, number: 1002, name: 'Brightwell' })
const charter = document({ docNumber: 10392, name: 'Charterparty – MV Northern Star.docx', fileVersion: '2.0', author: { username: 'jclarke', displayName: 'Jamie Clarke' } })
const lease = document({
  docNumber: 10447,
  name: 'Report on Lease.odt',
  matter: { id: 12, display: '1002.1 – Commercial Lease', client: brightwell },
  folder: { id: 121, name: 'Reports', kind: 'folder' },
})
const found = [charter, lease]

const facets: S['Facets'] = {
  type: [
    { value: 'word', count: 1 },
    { value: 'opendocument', count: 1 },
  ],
  author: [{ value: 'jclarke', displayName: 'Jamie Clarke', count: 1 }],
  class: [{ value: 'Correspondence', count: 2 }],
  client: [
    { value: '1001', displayName: '1001 – Saltmarsh Shipping Ltd', count: 1 },
    { value: '1002', displayName: '1002 – Brightwell', count: 1 },
  ],
  matter: [
    { value: '1001.1', displayName: '1001.1 – Charter Dispute', count: 1 },
    { value: '1002.1', displayName: '1002.1 – Commercial Lease', count: 1 },
  ],
}

function hits(items: unknown[], over: Record<string, unknown> = {}) {
  return { items, offset: 0, limit: 100, total: items.length, ...over }
}

function routes(over: Routes = {}): Routes {
  return {
    ...detailsRoutes(charter),
    'GET /v1/search': ({ query }: { query?: Record<string, unknown> }) => ({ ...hits(found), ...(query?.facets ? { facets } : {}) }),
    ...over,
  }
}

const main = () => screen.getByRole('main')
const grid = async () => within(main()).findByRole('grid')
// The list shows its header while the rows are on their way
const rows = async () => {
  const found = await grid()
  await waitFor(() => expect(within(found).queryByText('Loading…')).toBeNull())
  return within(found).getAllByRole('row').slice(1)
}
const funnel = () => within(main()).getByRole('button', { name: /^Filters/ })
const header = (name: string) => within(grid2()).getByRole('columnheader', { name: new RegExp(`^${name}`) })
const headerNames = () => within(grid2()).getAllByRole('columnheader').map((h) => h.textContent)

/** Opens a heading's menu by a right-click on it */
async function headingMenu(name: string) {
  expect(fireEvent.contextMenu(header(name))).toBe(false)
  return screen.findByRole('menu', { name: `${name} Column` })
}

describe('SearchView', () => {
  it('shows the words and where they were searched, the hits by relevance, and the count', async () => {
    const { api } = await renderApp('/search?scope=documents&q=charter', routes())
    expect(await within(main()).findByRole('heading', { level: 1, name: '"charter"' })).toBeTruthy()
    expect(within(main()).getByText('in Documents, by relevance')).toBeTruthy()
    const [first, second] = await rows()
    expect(within(first!).getByRole('rowheader').textContent).toBe('Charterparty – MV Northern Star.docx')
    expect(within(second!).getByRole('rowheader').textContent).toBe('Report on Lease.odt')
    expect(within(await grid()).getAllByRole('columnheader').map((h) => h.textContent)).toEqual(['Title', 'Doc #', 'Version', 'Matter', 'Author', 'Edit Date'])
    // By relevance, so no column is the sort
    expect(within(await grid()).getAllByRole('columnheader').some((h) => h.getAttribute('aria-sort') === 'ascending' || h.getAttribute('aria-sort') === 'descending')).toBe(false)
    expect(await within(main()).findByText('2 documents')).toBeTruthy()
    expect(api.callsTo('GET /v1/search')[0]?.query).toEqual({ scope: 'documents', q: 'charter', zone, offset: 0, limit: 100 })
  })

  it("shows each hit's own matter in the Matter column", async () => {
    await renderApp('/search?scope=documents&q=charter', routes())
    const [, second] = await rows()
    // What the cell holds, its tooltip and its text for a screen reader, is MatterCell's (listColumns.test.tsx)
    expect(within(second!).getAllByRole('gridcell').some((one) => one.textContent?.startsWith('1002.1 – Commercial Lease'))).toBe(true)
  })

  it('sorts by a header, asking the server, and reverses on a second click', async () => {
    const { api, router } = await renderApp('/search?scope=documents&q=charter', routes())
    await rows()
    fireEvent.click(within(await grid()).getByRole('columnheader', { name: /Doc #/ }))
    await waitFor(() => expect(api.callsTo('GET /v1/search').at(-1)?.query).toEqual({ scope: 'documents', q: 'charter', sort: 'docNumber', order: 'asc', zone, offset: 0, limit: 100 }))
    expect(new URLSearchParams(router.history.location.search).get('sort')).toBe('docNumber')
    expect(new URLSearchParams(router.history.location.search).get('q')).toBe('charter')
    await waitFor(() => expect(within(grid2()).getByRole('columnheader', { name: /Doc #/ }).getAttribute('aria-sort')).toBe('ascending'))
    fireEvent.click(within(grid2()).getByRole('columnheader', { name: /Doc #/ }))
    await waitFor(() => expect(api.callsTo('GET /v1/search').at(-1)?.query).toMatchObject({ sort: 'docNumber', order: 'desc' }))
  })

  it('every document column sorts on a search, asking the server: Date Filed and Sent newest first, the others A to Z', async () => {
    window.localStorage.setItem(
      'healdan.columns.documents',
      JSON.stringify({ v: 1, shown: ['title', 'docNumber', 'version', 'author', 'edited', 'matter', 'client', 'location', 'filed', 'size', 'type', 'class', 'subclass', 'typist', 'from', 'to', 'sent'], widths: {} }),
    )
    const { api, router } = await renderApp('/search?scope=documents&q=charter', routes())
    await rows()
    expect(headerNames()).toEqual(['Title', 'Doc #', 'Version', 'Author', 'Edit Date', 'Matter', 'Client', 'Location', 'Date Filed', 'Size', 'File Type', 'Class', 'Subclass', 'Typist', 'From', 'To', 'Sent'])
    // By relevance, so none is the sort, and every one can be
    expect(within(grid2()).getAllByRole('columnheader').map((one) => one.getAttribute('aria-sort'))).toEqual(Array(17).fill('none'))
    const firsts = {
      Matter: ['matter', 'asc'],
      Client: ['client', 'asc'],
      Location: ['location', 'asc'],
      'Date Filed': ['filed', 'desc'],
      Size: ['size', 'asc'],
      'File Type': ['type', 'asc'],
      Class: ['class', 'asc'],
      Subclass: ['subclass', 'asc'],
      Typist: ['typist', 'asc'],
      From: ['from', 'asc'],
      To: ['to', 'asc'],
      Sent: ['sent', 'desc'],
    }
    for (const [name, [sort, order]] of Object.entries(firsts)) {
      fireEvent.click(header(name))
      await waitFor(() => expect(api.callsTo('GET /v1/search').at(-1)?.query).toEqual({ scope: 'documents', q: 'charter', sort, order, zone, offset: 0, limit: 100 }))
      expect([new URLSearchParams(router.history.location.search).get('sort'), new URLSearchParams(router.history.location.search).get('order')]).toEqual([sort, order])
      await waitFor(() => expect(header(name).getAttribute('aria-sort')).toBe(order === 'asc' ? 'ascending' : 'descending'))
    }
  })

  it('sorts Edit Date newest first at its first click', async () => {
    const { api } = await renderApp('/search?scope=documents&q=charter', routes())
    await rows()
    fireEvent.click(within(await grid()).getByRole('columnheader', { name: /Edit Date/ }))
    await waitFor(() => expect(api.callsTo('GET /v1/search').at(-1)?.query).toMatchObject({ sort: 'edited', order: 'desc' }))
  })

  it('Sort by Relevance takes a sorted search back to relevance', async () => {
    const { api, router } = await renderApp('/search?scope=documents&q=charter&sort=title&order=desc&type=word', routes())
    await rows()
    expect(within(main()).getByText('in Documents')).toBeTruthy()
    // From any heading's menu while the search is sorted, beside the column's own sorts
    const menu = await headingMenu('Matter')
    expect(within(menu).getByRole('menuitemradio', { name: 'Sort Ascending' })).toBeTruthy()
    await userEvent.click(within(menu).getByRole('menuitem', { name: 'Sort by Relevance' }))
    // The address loses the sort and keeps the rest; it was replaced, so Back leaves the search
    await waitFor(() => expect(new URLSearchParams(router.history.location.search).has('sort')).toBe(false))
    const address = new URLSearchParams(router.history.location.search)
    expect(address.has('order')).toBe(false)
    expect([address.get('scope'), address.get('q'), address.getAll('type')]).toEqual(['documents', 'charter', ['word']])
    expect(router.history.length).toBe(1)
    expect(await within(main()).findByText('in Documents, by relevance')).toBeTruthy()
    await waitFor(() => expect(api.callsTo('GET /v1/search').at(-1)?.query).toEqual({ scope: 'documents', q: 'charter', type: ['word'], zone, offset: 0, limit: 100 }))
    await waitFor(() => expect(within(grid2()).getAllByRole('columnheader').map((h) => h.getAttribute('aria-sort'))).not.toContain('descending'))

    // By relevance already, no menu offers it
    expect(within(await headingMenu('Title')).queryByRole('menuitem', { name: 'Sort by Relevance' })).toBeNull()
  })

  it('chooses the columns of each document scope apart: the Emails search starts with From and Sent', async () => {
    const { view } = await renderApp('/search?scope=documents&q=charter', routes())
    await rows()
    await userEvent.click(within(await headingMenu('Author')).getByRole('menuitem', { name: 'Hide Author' }))
    await waitFor(() => expect(headerNames()).toEqual(['Title', 'Doc #', 'Version', 'Matter', 'Edit Date']))
    expect(JSON.parse(window.localStorage.getItem('healdan.columns.documents')!).shown).not.toContain('author')
    view.unmount()

    await renderApp('/search?scope=emails&q=charter', routes())
    await rows()
    expect(headerNames()).toEqual(['Title', 'Doc #', 'Matter', 'From', 'Sent'])
    await userEvent.click(within(main()).getByRole('button', { name: 'Columns' }))
    expect(await screen.findByRole('dialog', { name: 'Columns', description: 'For Emails Search' })).toBeTruthy()
    await userEvent.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Columns' })).toBeNull())
    expect(window.localStorage.getItem('healdan.columns.emails')).toBeNull()
  })

  it('keeps a sort, the filters and the scope from the address', async () => {
    const { api } = await renderApp('/search?scope=all&q=charter&sort=title&order=desc&type=pdf&matter=1001.1', routes())
    await rows()
    expect(api.callsTo('GET /v1/search')[0]?.query).toEqual({ scope: 'all', q: 'charter', sort: 'title', order: 'desc', type: ['pdf'], matter: ['1001.1'], zone, offset: 0, limit: 100 })
    expect(within(grid2()).getByRole('columnheader', { name: /Title/ }).getAttribute('aria-sort')).toBe('descending')
    expect(within(main()).getByText('in Docs & Emails')).toBeTruthy()
  })

  it("sends the person's time zone with a document search, so Today and the days of a range are theirs", async () => {
    const { api } = await renderApp('/search?scope=documents&q=charter&from=2026-11-01&to=2026-11-02', routes())
    await rows()
    expect(api.callsTo('GET /v1/search')[0]?.query).toMatchObject({ from: '2026-11-01', to: '2026-11-02', zone: Intl.DateTimeFormat().resolvedOptions().timeZone })
  })

  it('searches Emails in the emails scope', async () => {
    const { api } = await renderApp('/search?scope=emails&q=claim', routes())
    await rows()
    expect(api.callsTo('GET /v1/search')[0]?.query).toMatchObject({ scope: 'emails', q: 'claim' })
  })

  it('says "More than 10,000 documents" when the server stopped counting', async () => {
    await renderApp('/search?scope=documents&q=the', routes({ 'GET /v1/search': hits(found, { total: null, totalAtLeast: 10000 }) }))
    expect(await within(main()).findByText('More than 10,000 documents')).toBeTruthy()
  })

  it('pages on as the end of the list comes into view, to the end of what the server counted', async () => {
    const many = Array.from({ length: 150 }, (_, i) => document({ docNumber: 20000 + i, name: `Doc ${String(i).padStart(3, '0')}.docx` }))
    const { api } = await renderApp(
      '/search?scope=documents&q=doc',
      routes({
        'GET /v1/search': ({ query }: { query?: Record<string, unknown> }) => {
          const offset = Number(query?.offset ?? 0)
          return hits(many.slice(offset, offset + 100), { offset, total: 150 })
        },
      }),
    )
    expect(await within(await grid()).findByRole('row', { name: /Doc 000/ })).toBeTruthy()
    expect(await within(main()).findByText('150 documents')).toBeTruthy()
    act(() => {
      bringObservedIntoView()
      bringObservedIntoView()
    })
    expect(await within(await grid()).findByRole('row', { name: /Doc 120/ })).toBeTruthy()
    // The second page starts where the first ended, and the 150 counted are all there: nothing more is asked for
    act(() => bringObservedIntoView())
    await new Promise((resolve) => setTimeout(resolve, 100))
    expect(api.callsTo('GET /v1/search').map((call) => call.query?.offset)).toEqual([0, 100])
  })

  it('has Filters with Client and Matter besides Edit Date, File Type, Author and Class', async () => {
    const { api } = await renderApp('/search?scope=documents&q=charter', routes())
    await rows()
    await userEvent.click(funnel())
    const region = await screen.findByRole('region', { name: 'Filters' })
    await within(region).findByRole('checkbox', { name: 'Word, 1' })
    for (const group of ['File Type', 'Author', 'Class', 'Client', 'Matter']) expect(within(region).getByRole('group', { name: group })).toBeTruthy()
    expect(within(region).getByRole('checkbox', { name: '1001 – Saltmarsh Shipping Ltd, 1' })).toBeTruthy()
    expect(within(region).getByRole('checkbox', { name: '1001.1 – Charter Dispute, 1' })).toBeTruthy()
    expect(api.callsTo('GET /v1/search').some((c) => c.query?.facets === 'type,author,class,client,matter' && c.query?.limit === 1)).toBe(true)
  })

  it("names a client and a matter in the chips from the results, with Filters closed, and the number of one they don't show", async () => {
    await renderApp('/search?scope=documents&q=charter&client=1002&matter=1002.1&matter=1009.4', routes())
    await rows()
    const chips = within(within(main()).getByRole('group', { name: 'Filters in Use' }))
    expect(chips.getByText('Client: 1002 – Brightwell')).toBeTruthy()
    expect(chips.getByText('Matter: 1002.1 – Commercial Lease, 1009.4')).toBeTruthy()
  })

  it('filters through the address and the server, with the facets counted over the other filters', async () => {
    const { api, router } = await renderApp('/search?scope=documents&q=charter', routes())
    await rows()
    await userEvent.click(funnel())
    const region = await screen.findByRole('region', { name: 'Filters' })
    await userEvent.click(await within(region).findByRole('checkbox', { name: 'Word, 1' }))
    await waitFor(() => expect(new URLSearchParams(router.history.location.search).getAll('type')).toEqual(['word']))
    expect(new URLSearchParams(router.history.location.search).get('q')).toBe('charter')
    await waitFor(() => expect(api.callsTo('GET /v1/search').some((c) => (c.query?.type as string[] | undefined)?.[0] === 'word' && c.query?.offset === 0 && c.query?.limit === 100)).toBe(true))
    expect(within(main()).getByRole('group', { name: 'Filters in Use' })).toBeTruthy()
  })

  it("keeps the last answer's groups and counts on screen, dimmed, while a recount is held, never zeros", async () => {
    let release: () => void = () => {}
    const gate = new Promise<void>((resolve) => (release = resolve))
    await renderApp(
      '/search?scope=documents&q=charter',
      routes({
        'GET /v1/search': async ({ query }: { query?: Record<string, unknown> }) => {
          // The recount for the ticked type is held; the list and the first count answer at once
          if (query?.facets && query?.type) await gate
          return { ...hits(found), ...(query?.facets ? { facets } : {}) }
        },
      }),
    )
    await rows()
    await userEvent.click(funnel())
    const region = await screen.findByRole('region', { name: 'Filters' })
    await userEvent.click(await within(region).findByRole('checkbox', { name: 'Word, 1' }))
    await waitFor(() => expect(region.querySelector('[aria-busy="true"]')).toBeTruthy())
    for (const group of ['File Type', 'Author', 'Class', 'Client', 'Matter']) expect(within(region).getByRole('group', { name: group })).toBeTruthy()
    expect(within(region).getByRole('checkbox', { name: 'Word, 1' })).toBeTruthy()
    expect([...region.querySelectorAll('[data-count]')].map((one) => one.textContent)).not.toContain('0')
    release()
    await waitFor(() => expect(region.querySelector('[aria-busy="true"]')).toBeNull())
  })

  it('says when the counts come from the most relevant matches only, and offers a ticked value without a made-up count', async () => {
    await renderApp(
      '/search?scope=documents&q=charter&type=excel',
      routes({ 'GET /v1/search': ({ query }: { query?: Record<string, unknown> }) => ({ ...hits(found), ...(query?.facets ? { facets: { ...facets, partial: true } } : {}) }) }),
    )
    await rows()
    await userEvent.click(funnel())
    const region = await screen.findByRole('region', { name: 'Filters' })
    expect(await within(region).findByText('Counts from the most relevant matches')).toBeTruthy()
    // Excel is ticked and nothing among the most relevant matches offers it: shown, but not as "0"
    const excel = within(region).getByRole('checkbox', { name: 'Excel' })
    expect((excel as HTMLInputElement).checked).toBe(true)
    expect(within(region).queryByRole('checkbox', { name: 'Excel, 0' })).toBeNull()
  })

  it('shows the details of the selected document, with its Location', async () => {
    const { router } = await renderApp('/search?scope=documents&q=charter', routes())
    const [first] = await rows()
    await userEvent.click(within(first!).getByRole('rowheader'))
    await waitFor(() => expect(new URLSearchParams(router.history.location.search).get('sel')).toBe('10392'))
    const region = await screen.findByRole('region', { name: 'Details' })
    expect(await within(region).findByText('Location')).toBeTruthy()
  })

  // Each page and each count runs the whole search on the server (R96)
  it('asks again for what else is on screen when the person comes back to the window, but not the search or its counts, however old', async () => {
    const { api } = await renderApp('/search?scope=documents&q=charter', routes())
    await rows()
    await userEvent.click(funnel())
    const region = await screen.findByRole('region', { name: 'Filters' })
    await within(region).findByRole('checkbox', { name: 'Word, 1' })
    const searches = api.callsTo('GET /v1/search').length
    const me = api.callsTo('GET /v1/me').length
    expect(me).toBeGreaterThan(0)

    // Every answer is over the 30 seconds that keep it fresh, so the return asks again for what doesn't say otherwise
    const now = Date.now() + 31_000
    vi.spyOn(Date, 'now').mockReturnValue(now)
    onTestFinished(() => void vi.restoreAllMocks())
    comeBack()
    await waitFor(() => expect(api.callsTo('GET /v1/me').length).toBe(me + 1))
    await settle()
    expect(api.callsTo('GET /v1/search')).toHaveLength(searches)
  })

  it('says so when nothing is found, with filters or without', async () => {
    await renderApp('/search?scope=documents&q=zzz', routes({ 'GET /v1/search': hits([]) }))
    expect(await within(main()).findByText('Nothing found.')).toBeTruthy()
  })

  it('names what the search failed with, and tries again', async () => {
    let fail = true
    const { api } = await renderApp('/search?scope=documents&q=%22charter', routes({ 'GET /v1/search': () => (fail ? problem(400, 'bad-request') : hits(found)) }))
    expect(await within(main()).findByText("That request wasn't valid.")).toBeTruthy()
    fail = false
    await userEvent.click(within(main()).getByRole('button', { name: 'Try Again' }))
    await rows()
    expect(api.callsTo('GET /v1/search').length).toBeGreaterThan(1)
  })

  it('asks for something to search when the address has no words', async () => {
    const { api } = await renderApp('/search', routes())
    expect(await within(main()).findByText('Type in the search box to search.')).toBeTruthy()
    expect(api.callsTo('GET /v1/search')).toEqual([])
  })

  it('shows a folders search as folders, which open', async () => {
    const { router } = await renderApp(
      '/search?scope=folders&q=corr',
      routes({ 'GET /v1/search': hits([folder({ id: 111, name: 'Correspondence' })]) }),
    )
    expect(within(await grid()).getAllByRole('columnheader').map((h) => h.textContent)).toEqual(['Title', 'Matter'])
    const [row] = await rows()
    expect(within(row!).getByRole('gridcell').textContent).toContain('1001.1')
    // Folders take no filters; they sort, but start in the search's own order, which no column shows
    expect(within(main()).queryByRole('button', { name: /^Filters/ })).toBeNull()
    expect(within(await grid()).getAllByRole('columnheader').map((h) => h.getAttribute('aria-sort'))).toEqual(['none', 'none'])
    fireEvent.keyDown(row!, { key: 'Enter' })
    fireEvent.keyUp(row!, { key: 'Enter' })
    await waitFor(() => expect(router.history.location.pathname).toBe('/app/folders/111'))
  })

  it("the Folders scope's columns resize and keep their widths under its own name, with no Columns or Sort by Relevance", async () => {
    window.localStorage.setItem('healdan.columns.folders', JSON.stringify({ v: 1, widths: { title: 300 } }))
    await renderApp('/search?scope=folders&q=corr', routes({ 'GET /v1/search': hits([folder({ id: 111, name: 'Correspondence' })]) }))
    await rows()
    for (const one of within(await grid()).getAllByRole('columnheader')) expect(sliderOf(one)).toBeTruthy()
    // A kept width is drawn on its column
    await waitFor(() => expect(header('Title').style.width).toBe('300px'))
    expect(within(main()).queryByRole('button', { name: 'Columns' })).toBeNull()

    await resizeByKeys(header('Title'), ['ArrowRight', 'Enter'])
    await waitFor(() => expect(JSON.parse(window.localStorage.getItem('healdan.columns.folders')!).widths.title).toBe(310))
    expect(window.localStorage.getItem('healdan.columns.documents')).toBeNull()
    expect(window.localStorage.getItem('healdan.columns.matters')).toBeNull()

    // Its heading's menu sorts, resizes and resets, and neither chooses columns nor goes back to relevance, which a
    // folder search has none of
    const menu = await headingMenu('Title')
    expect(within(menu).getAllByRole('menuitemradio').map((item) => item.textContent)).toEqual(['Sort Ascending', 'Sort Descending'])
    expect(within(menu).getAllByRole('menuitem').map((item) => item.textContent)).toEqual(['Resize Column', 'Reset Columns'])
    await userEvent.click(within(menu).getByRole('menuitem', { name: 'Reset Columns' }))
    await waitFor(() => expect(window.localStorage.getItem('healdan.columns.folders')).toBeNull())
  })

  it('draws a folder by its kind: a search folder in the folders\' colour, and a matter\'s own folder as the matter', async () => {
    await renderApp(
      '/search?scope=folders&q=charter',
      routes({
        'GET /v1/search': hits([
          folder({ id: 111, name: 'Charter Correspondence' }),
          folder({ id: 112, name: 'Charter Docs Here', kind: 'searchFolder' }),
          folder({ id: 110, name: '1001.1 – Charter Dispute', kind: 'matter' }),
        ]),
      }),
    )
    await within(await grid()).findByRole('row', { name: /Charter Docs Here/ })
    const icons = (await rows()).map((row) => {
      const svg = row.querySelector('svg')!
      return [/tabler-icon-([\w-]+)/.exec(svg.getAttribute('class')!)![1], svg.getAttribute('data-kind')]
    })
    expect(icons).toEqual([
      ['folder', 'folder'],
      ['folder-search', 'folder'],
      ['briefcase', 'matter'],
    ])
  })

  it.each(['matters', 'clients'])('shows a %s search as matters, which open at their workspace', async (scope) => {
    const { router, api } = await renderApp(
      `/search?scope=${scope}&q=1001`,
      routes({ 'GET /v1/search': hits([matter({ id: 11, folderId: 110 })]) }),
    )
    expect(within(await grid()).getAllByRole('columnheader').map((h) => h.textContent)).toEqual(['Matter', 'Client'])
    // Resized, as every list is, under the scope's own name
    for (const one of within(await grid()).getAllByRole('columnheader')) expect(sliderOf(one)).toBeTruthy()
    const [row] = await rows()
    expect(row!.textContent).toContain('1001.1 – Charter Dispute')
    expect(row!.textContent).toContain('Saltmarsh Shipping Ltd')
    fireEvent.keyDown(row!, { key: 'Enter' })
    fireEvent.keyUp(row!, { key: 'Enter' })
    await waitFor(() => expect(router.history.location.pathname).toBe('/app/folders/110'))
    expect(api.callsTo('GET /v1/search')[0]?.query).toEqual({ scope, q: '1001', offset: 0, limit: 100 })
  })

  // Spec 5.2: the Folders scope sorts by Title and Matter, the Matters scope by Matter and Client, and the Clients scope by Client
  it.each([
    { scope: 'folders', sorts: { Title: 'title', Matter: 'matter' }, unsorted: [] },
    { scope: 'matters', sorts: { Matter: 'matter', Client: 'client' }, unsorted: [] },
    { scope: 'clients', sorts: { Client: 'client' }, unsorted: ['Matter'] },
  ])('the $scope search sorts by its own columns, asking the server, with the sort in the address', async ({ scope, sorts, unsorted }) => {
    const found = scope === 'folders' ? folder({ id: 111, name: 'Correspondence' }) : matter({ id: 11, folderId: 110 })
    const { api, router } = await renderApp(`/search?scope=${scope}&q=1001`, routes({ 'GET /v1/search': hits([found], { total: null }) }))
    await rows()
    const address = () => new URLSearchParams(router.history.location.search)
    for (const [name, sort] of Object.entries(sorts)) {
      // A to Z, then the other way; no filters or time zone, which these scopes don't take
      fireEvent.click(header(name))
      await waitFor(() => expect(api.callsTo('GET /v1/search').at(-1)?.query).toEqual({ scope, q: '1001', sort, order: 'asc', offset: 0, limit: 100 }))
      expect([address().get('scope'), address().get('q'), address().get('sort'), address().get('order')]).toEqual([scope, '1001', sort, 'asc'])
      await waitFor(() => expect(header(name).getAttribute('aria-sort')).toBe('ascending'))
      fireEvent.click(header(name))
      await waitFor(() => expect(api.callsTo('GET /v1/search').at(-1)?.query).toEqual({ scope, q: '1001', sort, order: 'desc', offset: 0, limit: 100 }))
      await waitFor(() => expect(header(name).getAttribute('aria-sort')).toBe('descending'))
    }
    // Each sort replaced the address, so Back leaves the search
    expect(router.history.length).toBe(1)
    // A heading that doesn't sort here has no arrow, and a click on it changes nothing: a sort would be in the address as
    // the click ends, as the others' were
    const calls = api.callsTo('GET /v1/search').length
    const last = router.history.location.search
    for (const name of unsorted) {
      expect(header(name).getAttribute('aria-sort')).toBeNull()
      fireEvent.click(header(name))
      expect(router.history.location.search).toBe(last)
      expect(api.callsTo('GET /v1/search')).toHaveLength(calls)
    }
    // Sorted, its menus still have no Sort by Relevance: it isn't a search of documents, which has none
    const menu = await headingMenu(Object.keys(sorts)[0]!)
    expect(within(menu).getAllByRole('menuitemradio').map((item) => item.textContent)).toEqual(['Sort Ascending', 'Sort Descending'])
    expect(within(menu).queryByRole('menuitem', { name: 'Sort by Relevance' })).toBeNull()
  })

  it("keeps a scope's sort from the address, and drops one the scope doesn't take", async () => {
    const one = (scope: string) => ({ 'GET /v1/search': hits([scope === 'folders' ? folder() : matter()], { total: null }) })
    const first = await renderApp('/search?scope=matters&q=1001&sort=client&order=desc', routes(one('matters')))
    await rows()
    expect(first.api.callsTo('GET /v1/search')[0]?.query).toEqual({ scope: 'matters', q: '1001', sort: 'client', order: 'desc', offset: 0, limit: 100 })
    expect(header('Client').getAttribute('aria-sort')).toBe('descending')
    first.view.unmount()

    // A document's sort, or another scope's, is no sort: the scope's own order, as for a sort no list knows
    for (const [scope, sort] of [
      ['clients', 'matter'],
      ['folders', 'docNumber'],
      ['matters', 'title'],
    ] as const) {
      const { api, view } = await renderApp(`/search?scope=${scope}&q=1001&sort=${sort}&order=asc`, routes(one(scope)))
      await rows()
      expect(api.callsTo('GET /v1/search')[0]?.query, `${scope} ${sort}`).toEqual({ scope, q: '1001', offset: 0, limit: 100 })
      expect(within(grid2()).getAllByRole('columnheader').map((h) => h.getAttribute('aria-sort'))).not.toContain('ascending')
      view.unmount()
    }
  })

  it('pages an uncounted folders search on while its pages are full, and ends at the first short one', async () => {
    const all = Array.from({ length: 120 }, (_, i) => folder({ id: 500 + i, name: `Folder ${String(i).padStart(3, '0')}` }))
    const { api } = await renderApp(
      '/search?scope=folders&q=folder',
      routes({
        'GET /v1/search': ({ query }: { query?: Record<string, unknown> }) => {
          const offset = Number(query?.offset ?? 0)
          // The server counts nothing for a folders search: total is null
          return { items: all.slice(offset, offset + 100), offset, limit: 100, total: null }
        },
      }),
    )
    expect(await within(await grid()).findByRole('row', { name: /Folder 000/ })).toBeTruthy()
    act(() => {
      bringObservedIntoView()
      bringObservedIntoView()
    })
    expect(await within(await grid()).findByRole('row', { name: /Folder 110/ })).toBeTruthy()
    // 20 is a short page, so it was the last
    act(() => bringObservedIntoView())
    await new Promise((resolve) => setTimeout(resolve, 100))
    expect(api.callsTo('GET /v1/search').map((call) => call.query?.offset)).toEqual([0, 100])
  })

  it('passes axe, with Filters open', async () => {
    await renderApp('/search?scope=documents&q=charter', routes())
    await rows()
    await userEvent.click(funnel())
    await within(await screen.findByRole('region', { name: 'Filters' })).findByRole('checkbox', { name: 'Word, 1' })
    await expectNoAxeViolations(main())
  })
})

// The grid after a re-render, which is a new element
const grid2 = () => within(main()).getByRole('grid')
