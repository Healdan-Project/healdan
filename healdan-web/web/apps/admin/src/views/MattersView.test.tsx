// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { act, fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { formatDate, formatDateTime, formatSize, setLabels } from '@healdan/ui'
import { expectNoAxeViolations } from '../test/axe'
import { clientList, clientOf, findPeople, matterList, matterRow, matterRows, problem, sally, saltmarsh, tom } from '../test/fakeApi'
import { bringObservedIntoView } from '../test/intersection'
import { renderApp } from '../test/renderApp'
import { TYPING_DELAY_MS } from '../pickers/typing'

const main = () => screen.getByRole('main')
const grid = () => within(main()).findByRole('grid', { name: 'Matters' })
const filters = () => within(main()).getByRole('group', { name: 'Filter Matters' })
/** Long enough for the typing to pause and the answer to come */
const pause = () => act(() => new Promise((resolve) => setTimeout(resolve, TYPING_DELAY_MS + 100)))
const cellsOf = (row: HTMLElement) => Array.from(row.querySelectorAll('[role="rowheader"], [role="gridcell"]'), (cell) => cell.textContent)
const box = (name: string) => within(filters()).getByRole('combobox', { name }) as HTMLInputElement
const lastQuery = (api: { callsTo: (pattern: string) => { query?: Record<string, unknown> }[] }) => api.callsTo('GET /v1/admin/matters').at(-1)?.query

/** The matters' rows once the list has come, the header's left out */
async function rows() {
  const table = await grid()
  await within(table).findByRole('row', { name: /998\.1/ })
  return within(table).getAllByRole('row').slice(1)
}

describe('The Matters list', () => {
  it("is the Firm group's Matters, at /admin/matters, every column for each matter, and how many there are", async () => {
    const { view, router } = await renderApp('/')
    const pane = screen.getByRole('navigation', { name: 'Navigation' })
    await userEvent.click(within(pane).getByRole('link', { name: 'Matters' }))
    expect(router.state.location.pathname).toBe('/matters')
    expect(screen.getByRole('heading', { level: 1, name: 'Matters' })).toBeTruthy()
    const table = await grid()
    expect(within(table).getAllByRole('columnheader').map((one) => one.textContent)).toEqual([
      'Matter',
      'Name',
      'Client',
      'Store',
      'Owner',
      'Template',
      'Documents',
      'Size',
      'Last Activity',
    ])
    const all = await rows()
    expect(all).toHaveLength(matterRows.length)
    expect(cellsOf(all[2]!)).toEqual([
      '1001.1',
      'Charter Dispute – MV Northern Star',
      'Saltmarsh Shipping Ltd',
      'Client Work',
      'Tom Reeve',
      'Litigation',
      '412',
      formatSize(matterRows[2]!.bytes!),
      formatDate(matterRows[2]!.lastActivity!),
    ])
    // Whose owner's account has gone, and which was made from no template, has nothing in those cells
    expect(cellsOf(all[7]!).slice(0, 6)).toEqual(['1003.2', 'Licence Review', 'Kestrel Software Ltd', 'Client Work', '', ''])
    // Each row is named by its number and name, for a screen reader
    expect(within(table).getByRole('row', { name: '1001.1 Charter Dispute – MV Northern Star' })).toBeTruthy()
    expect(within(main()).getByText('8 Matters')).toBeTruthy()
    expect(within(main()).getByText(`Documents, Size and Last Activity as of ${formatDateTime('2026-11-19T10:41:00Z')}`)).toBeTruthy()
    await expectNoAxeViolations(view.container)
  })

  it('asks for the first 100 matters, in the order the server keeps by default', async () => {
    const { api } = await renderApp('/matters')
    await rows()
    expect(api.callsTo('GET /v1/admin/matters').map((call) => call.query)).toEqual([{ offset: 0, limit: 100 }])
  })

  it('says the figures are still to come, before the server has worked them out since it started', async () => {
    const pending = matterRows.map((one) => ({ ...one, documents: null, bytes: null, lastActivity: null }))
    const { view } = await renderApp('/matters', { 'GET /v1/admin/matters': matterList(pending, { figuresAt: null }) })
    const all = await rows()
    expect(cellsOf(all[2]!).slice(6)).toEqual(['', '', ''])
    expect(
      within(main()).getByText("Documents, Size and Last Activity haven't been worked out since the server started. They show here within a few minutes."),
    ).toBeTruthy()
    await expectNoAxeViolations(view.container)
  })

  it('sorts by each column, asking the server, ascending first and then the other way, and the address keeps the order', async () => {
    const { api, router } = await renderApp('/matters')
    const table = await grid()
    await rows()
    // The server's own order is by number, which the Matter column says
    expect(within(table).getByRole('columnheader', { name: 'Matter' }).getAttribute('aria-sort')).toBe('ascending')
    const sorts: Record<string, string> = {
      Name: 'name',
      Client: 'client',
      Store: 'store',
      Owner: 'owner',
      Template: 'template',
      Documents: 'documents',
      Size: 'bytes',
      'Last Activity': 'lastActivity',
    }
    for (const [name, sort] of Object.entries(sorts)) {
      await userEvent.click(within(table).getByRole('columnheader', { name }))
      await waitFor(() => expect(lastQuery(api)).toEqual({ sort, order: 'asc', offset: 0, limit: 100 }))
      expect(router.state.location.search).toEqual({ sort, order: 'asc' })
    }
    await userEvent.click(within(table).getByRole('columnheader', { name: 'Last Activity' }))
    await waitFor(() => expect(lastQuery(api)).toEqual({ sort: 'lastActivity', order: 'desc', offset: 0, limit: 100 }))
    await waitFor(() => expect(within(table).getByRole('columnheader', { name: 'Last Activity' }).getAttribute('aria-sort')).toBe('descending'))
    await userEvent.click(within(table).getByRole('columnheader', { name: 'Matter' }))
    await waitFor(() => expect(lastQuery(api)).toEqual({ sort: 'number', order: 'asc', offset: 0, limit: 100 }))
  })

  it('takes its order and filters from the address, and leaves out what it doesn\'t know', async () => {
    const first = await renderApp('/matters?sort=owner&order=desc&q=lease&client=2&store=4&owner=5&sort2=x')
    await grid()
    await waitFor(() => expect(lastQuery(first.api)).toEqual({ q: 'lease', clientId: 2, storeId: 4, ownerId: 5, sort: 'owner', order: 'desc', offset: 0, limit: 100 }))
    first.view.unmount()
    const { api: other } = await renderApp('/matters?sort=size&order=up&client=two&store=-1&owner=1.5')
    await waitFor(() => expect(other.callsTo('GET /v1/admin/matters')).toHaveLength(1))
    expect(lastQuery(other)).toEqual({ offset: 0, limit: 100 })
  })

  it('filters by the number or name typed, once the typing pauses, and the address keeps it', async () => {
    const { api, router } = await renderApp('/matters')
    await rows()
    const field = within(filters()).getByRole('textbox', { name: 'Number or Name' })
    await userEvent.type(field, ' 1001 ')
    await pause()
    expect(lastQuery(api)).toEqual({ q: '1001', offset: 0, limit: 100 })
    // One question for the words once they settled, not one for each key
    expect(api.callsTo('GET /v1/admin/matters')).toHaveLength(2)
    expect(router.state.location.search).toEqual({ q: '1001' })
    // The field keeps what was typed, spaces and all
    expect((field as HTMLInputElement).value).toBe(' 1001 ')
  })

  it('has Client and Owner as searches, and Store as a menu of the Stores', async () => {
    await renderApp('/matters')
    await rows()
    const client = within(filters()).getByRole('combobox', { name: 'Client' })
    const owner = within(filters()).getByRole('combobox', { name: 'Owner' })
    const store = within(filters()).getByRole('combobox', { name: 'Store' })
    // A search is typed into, and finds as it goes; the menu offers every Store
    expect(client.tagName).toBe('INPUT')
    expect(client.getAttribute('aria-autocomplete')).toBe('list')
    expect(owner.tagName).toBe('INPUT')
    expect(owner.getAttribute('aria-autocomplete')).toBe('list')
    expect(store.tagName).toBe('SELECT')
    await waitFor(() => expect(within(store).getAllByRole('option').map((one) => one.textContent)).toEqual(['All Stores', 'Client Work', 'Firm']))
  })

  it('filters by the client picked from its search', async () => {
    const { api, router } = await renderApp('/matters', {
      'GET /v1/admin/clients': ({ query }: { query?: Record<string, unknown> }) => clientList(String(query?.q).startsWith('salt') ? [clientOf(saltmarsh)] : []),
    })
    await rows()
    await userEvent.type(box('Client'), 'salt')
    await pause()
    await userEvent.click(await screen.findByRole('option', { name: '1001 – Saltmarsh Shipping Ltd' }))
    // The pick shows at once, so its list closes, before the address has followed
    expect(screen.queryByRole('listbox')).toBeNull()
    await waitFor(() => expect(lastQuery(api)).toEqual({ clientId: 1, offset: 0, limit: 100 }))
    expect(router.state.location.search).toEqual({ client: '1' })
    expect(box('Client').value).toBe('1001 – Saltmarsh Shipping Ltd')
  })

  it('filters by the owner picked from the people search', async () => {
    const { api, router } = await renderApp('/matters', { 'GET /v1/admin/find': ({ query }: { query?: Record<string, unknown> }) => findPeople(query?.q) })
    await rows()
    await userEvent.type(box('Owner'), 'tom')
    await pause()
    await userEvent.click(await screen.findByRole('option', { name: 'Tom Reeve tom' }))
    expect(screen.queryByRole('listbox')).toBeNull()
    await waitFor(() => expect(lastQuery(api)).toEqual({ ownerId: tom.id, offset: 0, limit: 100 }))
    expect(router.state.location.search).toEqual({ owner: String(tom.id) })
    expect(box('Owner').value).toBe('Tom Reeve')
  })

  it('finds an owner who has left, as their matters stay theirs, and says they have', async () => {
    const left = matterRow({ id: 41, number: '1004.1', name: 'Grant Agreement', owner: { id: sally.id, username: sally.username, name: sally.name } })
    const { api, view } = await renderApp('/matters', {
      'GET /v1/admin/find': ({ query }: { query?: Record<string, unknown> }) => findPeople(query?.q),
      'GET /v1/admin/matters': ({ query }: { query?: Record<string, unknown> }) => matterList(query?.ownerId === sally.id ? [left] : matterRows),
    })
    await rows()
    await userEvent.type(box('Owner'), 'oldham')
    await pause()
    const option = await screen.findByRole('option', { name: 'Sally Oldham sally.old · Disabled' })
    // The people the admin find gives, those who have left among them; GET /v1/users gives only those who can sign in
    expect(api.callsTo('GET /v1/admin/find').at(-1)?.query).toEqual({ q: 'oldham' })
    expect(api.callsTo('GET /v1/users')).toEqual([])
    await expectNoAxeViolations(view.container)
    await userEvent.click(option)
    await waitFor(() => expect(lastQuery(api)).toEqual({ ownerId: sally.id, offset: 0, limit: 100 }))
    expect(await within(await grid()).findByRole('row', { name: '1004.1 Grant Agreement' })).toBeTruthy()
    expect(box('Owner').value).toBe('Sally Oldham')
  })

  it('filters by the Store picked from its menu, and All Stores takes the filter off', async () => {
    const { api, router } = await renderApp('/matters')
    await rows()
    const store = within(filters()).getByRole('combobox', { name: 'Store' })
    await waitFor(() => expect(within(store).getAllByRole('option')).toHaveLength(3))
    await userEvent.selectOptions(store, 'Firm')
    await waitFor(() => expect(lastQuery(api)).toEqual({ storeId: 3, offset: 0, limit: 100 }))
    expect(router.state.location.search).toEqual({ store: '3' })
    await userEvent.selectOptions(store, 'All Stores')
    await waitFor(() => expect(router.state.location.search).toEqual({}))
  })

  it('names a client and an owner the address filters by from the rows, and a client no row names from the server', async () => {
    const first = await renderApp('/matters?client=1&owner=6', { 'GET /v1/admin/matters': matterList([matterRow()]) })
    await within(await grid()).findByRole('row', { name: /1001\.1/ })
    await waitFor(() => expect(box('Client').value).toBe('1001 – Saltmarsh Shipping Ltd'))
    expect(box('Owner').value).toBe('Tom Reeve')
    expect(first.api.callsTo('GET /v1/admin/clients/*')).toEqual([])
    first.view.unmount()

    const { api } = await renderApp('/matters?client=1&q=nothing', {
      'GET /v1/admin/matters': matterList([]),
      'GET /v1/admin/clients/*': ({ params }: { params: string[] }) => (params[0] === '1' ? clientOf(saltmarsh) : problem(404, 'not-found')),
    })
    await within(main()).findByText('No matter matches the filters.')
    await waitFor(() => expect(box('Client').value).toBe('1001 – Saltmarsh Shipping Ltd'))
    expect(api.callsTo('GET /v1/admin/clients/*').map((call) => call.path)).toEqual(['/v1/admin/clients/1'])
  })

  it('clears every filter with Clear Filters, keeping the order, and has none to clear without one', async () => {
    const { router } = await renderApp('/matters?q=lease&store=4&sort=name')
    await grid()
    expect(within(filters()).getByRole('textbox', { name: 'Number or Name' })).toHaveProperty('value', 'lease')
    await userEvent.click(within(main()).getByRole('button', { name: 'Clear Filters' }))
    await waitFor(() => expect(router.state.location.search).toEqual({ sort: 'name' }))
    expect(within(filters()).getByRole('textbox', { name: 'Number or Name' })).toHaveProperty('value', '')
    expect(within(main()).queryByRole('button', { name: 'Clear Filters' })).toBeNull()
  })

  it('follows the address when it changes from outside, as the pane\'s Matters takes the filters off', async () => {
    const { router } = await renderApp('/matters?q=lease')
    await grid()
    const field = within(filters()).getByRole('textbox', { name: 'Number or Name' })
    expect(field).toHaveProperty('value', 'lease')
    await userEvent.click(within(screen.getByRole('navigation', { name: 'Navigation' })).getByRole('link', { name: 'Matters' }))
    await waitFor(() => expect(router.state.location.search).toEqual({}))
    await waitFor(() => expect(field).toHaveProperty('value', ''))
    // And nothing typed before comes back once the typing would have paused
    await pause()
    expect(router.state.location.search).toEqual({})
  })

  it('loads 100 rows at a time as the end of the list comes into view, to the end of what the server counted', async () => {
    const many = Array.from({ length: 201 }, (_, i) => matterRow({ id: 1000 + i, number: `2000.${i + 1}`, name: `Matter ${String(i + 1).padStart(3, '0')}` }))
    const { api } = await renderApp('/matters', {
      'GET /v1/admin/matters': ({ query }: { query?: Record<string, unknown> }) => {
        const offset = Number(query?.offset ?? 0)
        return matterList(many.slice(offset, offset + Number(query?.limit)), { offset, total: many.length })
      },
    })
    const table = await grid()
    expect(await within(table).findByRole('row', { name: '2000.1 Matter 001' })).toBeTruthy()
    expect(within(main()).getByText('201 Matters')).toBeTruthy()
    act(() => bringObservedIntoView())
    expect(await within(table).findByRole('row', { name: '2000.150 Matter 150' })).toBeTruthy()
    act(() => bringObservedIntoView())
    expect(await within(table).findByRole('row', { name: '2000.201 Matter 201' })).toBeTruthy()
    act(() => bringObservedIntoView())
    await new Promise((resolve) => setTimeout(resolve, 100))
    expect(api.callsTo('GET /v1/admin/matters').map((call) => [call.query?.offset, call.query?.limit])).toEqual([
      [0, 100],
      [100, 100],
      [200, 100],
    ])
  })

  it("opens a matter's page when its row is clicked, or Enter is pressed on it", async () => {
    const first = await renderApp('/matters')
    const table = await grid()
    await rows()
    await userEvent.click(within(table).getByRole('row', { name: /1001\.1/ }))
    await waitFor(() => expect(first.router.state.location.pathname).toBe('/matters/11'))
    // The list's entry in the pane stays the open screen on a matter's page
    expect(within(screen.getByRole('navigation', { name: 'Navigation' })).getByRole('link', { name: 'Matters' }).getAttribute('aria-current')).toBe('page')

    first.view.unmount()
    const { router: other } = await renderApp('/matters')
    const again = await grid()
    await rows()
    const row = within(again).getByRole('row', { name: /1002\.2/ })
    act(() => row.focus())
    fireEvent.keyDown(row, { key: 'Enter' })
    fireEvent.keyUp(row, { key: 'Enter' })
    await waitFor(() => expect(other.state.location.pathname).toBe('/matters/22'))
  })

  it('says when no matter has been made yet, and when none matches the filters', async () => {
    const { view } = await renderApp('/matters', { 'GET /v1/admin/matters': matterList([]) })
    expect(await within(main()).findByText('No matter has been made yet.')).toBeTruthy()
    await expectNoAxeViolations(view.container)
    view.unmount()
    await renderApp('/matters?q=zzz', { 'GET /v1/admin/matters': matterList([]) })
    expect(await within(main()).findByText('No matter matches the filters.')).toBeTruthy()
  })

  it("shows the catalogue's message when the list can't be had, with Try Again", async () => {
    let fail = true
    const { api } = await renderApp('/matters', { 'GET /v1/admin/matters': () => (fail ? problem(500, 'server-error') : matterList(matterRows)) })
    expect(await within(main()).findByText('Something went wrong.')).toBeTruthy()
    expect(within(main()).queryByText('A server sentence that must not be shown raw.')).toBeNull()
    fail = false
    await userEvent.click(within(main()).getByRole('button', { name: 'Try Again' }))
    await rows()
    expect(api.callsTo('GET /v1/admin/matters')).toHaveLength(2)
  })

  it("uses the firm's own word for a matter", async () => {
    setLabels({ matter: 'Project', matters: 'Projects' })
    await renderApp('/matters')
    expect(screen.getByRole('heading', { level: 1, name: 'Projects' })).toBeTruthy()
    const table = await within(main()).findByRole('grid', { name: 'Projects' })
    expect(within(table).getAllByRole('columnheader')[0]?.textContent).toBe('Project')
    expect(within(screen.getByRole('navigation', { name: 'Navigation' })).getByRole('link', { name: 'Projects' })).toBeTruthy()
  })
})
