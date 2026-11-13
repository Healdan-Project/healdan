// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { act, fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import type { components } from '@healdan/api'
import { expectNoAxeViolations } from '../test/axe'
import { client, document, folder, matter, problem, zone, type Routes } from '../test/fakeApi'
import { detailsRoutes } from '../test/details'
import { bringObservedIntoView } from '../test/intersection'
import { renderApp } from '../test/renderApp'
import viewsCss from './views.module.css?raw'

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
    { value: '1001', count: 1 },
    { value: '1002', count: 1 },
  ],
  matter: [
    { value: '1001.1', count: 1 },
    { value: '1002.1', count: 1 },
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

  it('puts the matter number with the client muted in the Matter column, and the matter name where a keyboard and a screen reader reach it', async () => {
    await renderApp('/search?scope=documents&q=charter', routes())
    const [, second] = await rows()
    const cell = within(second!).getAllByRole('gridcell').find((one) => one.textContent?.includes('Brightwell'))!
    expect(cell.textContent).toContain('1002.1')
    expect(cell.textContent).toContain('Brightwell')
    // Hover shows the name, and so does the text a screen reader reads with the cell
    expect(cell.querySelector('[title="Commercial Lease"]')).toBeTruthy()
    expect(within(cell).getByText('Commercial Lease').className).toMatch(/matterName/)
    expect(within(cell).getByText('Brightwell').className).toMatch(/matterClient/)
    // A keyboard user sees the name too: the row's focus (an arrow key puts it there) swaps the client for it, as
    // hovering does. jsdom draws no style, so the stylesheet is read.
    expect(viewsCss).toMatch(/\[role='row'\]:focus-within\)\s+\.matterName/)
    expect(viewsCss).toMatch(/\[role='row'\]:hover\)\s+\.matterName/)
    const row = second!
    row.focus()
    expect(row.matches(':focus-within')).toBe(true)
  })

  it('sorts by a header, asking the server, and reverses on a second click', async () => {
    const { api, router } = await renderApp('/search?scope=documents&q=charter', routes())
    await rows()
    fireEvent.click(within(await grid()).getByRole('columnheader', { name: /Edit Date/ }))
    await waitFor(() => expect(api.callsTo('GET /v1/search').at(-1)?.query).toEqual({ scope: 'documents', q: 'charter', sort: 'edited', order: 'asc', zone, offset: 0, limit: 100 }))
    expect(new URLSearchParams(router.history.location.search).get('sort')).toBe('edited')
    expect(new URLSearchParams(router.history.location.search).get('q')).toBe('charter')
    await waitFor(() => expect(within(grid2()).getByRole('columnheader', { name: /Edit Date/ }).getAttribute('aria-sort')).toBe('ascending'))
    fireEvent.click(within(grid2()).getByRole('columnheader', { name: /Edit Date/ }))
    await waitFor(() => expect(api.callsTo('GET /v1/search').at(-1)?.query).toMatchObject({ sort: 'edited', order: 'desc' }))
    // The Matter column has nothing to sort by
    expect(within(grid2()).getByRole('columnheader', { name: /Matter/ }).getAttribute('aria-sort')).toBeNull()
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
    expect(within(region).getByRole('checkbox', { name: '1001.1, 1' })).toBeTruthy()
    expect(api.callsTo('GET /v1/search').some((c) => c.query?.facets === 'type,author,class,client,matter' && c.query?.limit === 1)).toBe(true)
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
  it('asks again for what else is on screen when the person comes back to the window, but not the search or its counts', async () => {
    const { api } = await renderApp('/search?scope=documents&q=charter', routes())
    await rows()
    await userEvent.click(funnel())
    const region = await screen.findByRole('region', { name: 'Filters' })
    await within(region).findByRole('checkbox', { name: 'Word, 1' })
    const searches = api.callsTo('GET /v1/search').length
    const me = api.callsTo('GET /v1/me').length
    expect(me).toBeGreaterThan(0)

    act(() => void window.dispatchEvent(new Event('focus')))
    await waitFor(() => expect(api.callsTo('GET /v1/me').length).toBe(me + 1))
    await act(() => new Promise((resolve) => setTimeout(resolve, 20)))
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
    // Folders take no filters or sorting
    expect(within(main()).queryByRole('button', { name: /^Filters/ })).toBeNull()
    expect(within(await grid()).getAllByRole('columnheader').every((h) => h.getAttribute('aria-sort') === null)).toBe(true)
    fireEvent.keyDown(row!, { key: 'Enter' })
    fireEvent.keyUp(row!, { key: 'Enter' })
    await waitFor(() => expect(router.history.location.pathname).toBe('/app/folders/111'))
  })

  it.each(['matters', 'clients'])('shows a %s search as matters, which open at their workspace', async (scope) => {
    const { router, api } = await renderApp(
      `/search?scope=${scope}&q=1001`,
      routes({ 'GET /v1/search': hits([matter({ id: 11, folderId: 110 })]) }),
    )
    expect(within(await grid()).getAllByRole('columnheader').map((h) => h.textContent)).toEqual(['Matter', 'Client'])
    const [row] = await rows()
    expect(row!.textContent).toContain('1001.1 – Charter Dispute')
    expect(row!.textContent).toContain('Saltmarsh Shipping Ltd')
    fireEvent.keyDown(row!, { key: 'Enter' })
    fireEvent.keyUp(row!, { key: 'Enter' })
    await waitFor(() => expect(router.history.location.pathname).toBe('/app/folders/110'))
    expect(api.callsTo('GET /v1/search')[0]?.query).toEqual({ scope, q: '1001', offset: 0, limit: 100 })
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
