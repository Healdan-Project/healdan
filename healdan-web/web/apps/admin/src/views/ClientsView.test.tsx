// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { act, fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { ApiError } from '@healdan/api'
import { formatDate, formatDateTime, setLabels } from '@healdan/ui'
import { expectNoAxeViolations } from '../test/axe'
import { clientList, clientOf, clientRows, clients, problem, type FakeApi } from '../test/fakeApi'
import { bringObservedIntoView } from '../test/intersection'
import { setListSize } from '../test/listSize'
import { renderApp } from '../test/renderApp'
import { TYPING_DELAY_MS } from '../pickers/typing'

const main = () => screen.getByRole('main')
const pane = () => screen.getByRole('navigation', { name: 'Navigation' })
const grid = () => within(main()).findByRole('grid', { name: 'Clients' })
const filters = () => within(main()).getByRole('group', { name: 'Filter Clients' })
/** Long enough for the typing to pause and the answer to come */
const pause = () => act(() => new Promise((resolve) => setTimeout(resolve, TYPING_DELAY_MS + 100)))
const cellsOf = (row: HTMLElement) => Array.from(row.querySelectorAll('[role="rowheader"], [role="gridcell"]'), (cell) => cell.textContent)
/** The list's own requests: a page of clients from an offset, which a search of the dialog's never asks for */
const listCalls = (api: FakeApi) => api.callsTo('GET /v1/admin/clients').filter((call) => call.query?.offset !== undefined)
const lastQuery = (api: FakeApi) => listCalls(api).at(-1)?.query
const LIST = { 'GET /v1/admin/clients': ({ query }: { query?: Record<string, unknown> }) => clients(query) }

/** The clients' rows once the list has come, the header's left out */
async function rows() {
  const table = await grid()
  await within(table).findByRole('row', { name: /^998 / })
  return within(table).getAllByRole('row').slice(1)
}

describe('The Clients list', () => {
  it("is the Firm group's Clients, before Matters, at /admin/clients: each client's number, name, matters and figures, and how many there are", async () => {
    const { view, router } = await renderApp('/', LIST)
    const firm = within(pane()).getByRole('region', { name: 'Firm' })
    expect(within(firm).getAllByRole('link').map((link) => link.textContent)).toEqual(['Clients', 'Matters', 'Workspace Templates'])
    await userEvent.click(within(pane()).getByRole('link', { name: 'Clients' }))
    expect(router.state.location.pathname).toBe('/clients')
    expect(screen.getByRole('heading', { level: 1, name: 'Clients' })).toBeTruthy()
    const table = await grid()
    expect(within(table).getAllByRole('columnheader').map((one) => one.textContent)).toEqual(['Number', 'Name', 'Matters', 'Documents', 'Last Activity'])
    const all = await rows()
    expect(all.map(cellsOf)).toEqual([
      ['998', 'Ashcombe Reeve LLP', '2', '127', formatDate('2026-11-12T09:00:00Z')],
      ['1001', 'Saltmarsh Shipping Ltd', '2', '508', formatDate('2026-11-19T10:12:00Z')],
      ['1002', 'Brightwell Bakeries Ltd', '2', '221', formatDate('2026-11-18T16:20:00Z')],
      ['1003', 'Kestrel Software Ltd', '2', '235', formatDate('2026-11-19T09:05:00Z')],
      // A client with no matter has nothing filed, and no last activity
      ['1004', 'Marsh Lane Community Trust', '0', '0', ''],
    ])
    // Each row is named by its number and name, for a screen reader
    expect(within(table).getByRole('row', { name: '1001 Saltmarsh Shipping Ltd' })).toBeTruthy()
    expect(within(main()).getByText('5 Clients')).toBeTruthy()
    expect(within(main()).getByText(`Documents and Last Activity as of ${formatDateTime('2026-11-19T10:41:00Z')}`)).toBeTruthy()
    expect(within(pane()).getByRole('link', { name: 'Clients' }).getAttribute('aria-current')).toBe('page')
    await expectNoAxeViolations(view.container)
  })

  it('asks for the first 100 clients, in the order the server keeps by default', async () => {
    const { api } = await renderApp('/clients', LIST)
    await rows()
    expect(listCalls(api).map((call) => call.query)).toEqual([{ offset: 0, limit: 100 }])
  })

  it('says the figures are still to come, before the server has worked them out since it started', async () => {
    const pending = clientRows.map((one) => ({ ...one, documents: null, lastActivity: null }))
    const { view } = await renderApp('/clients', { 'GET /v1/admin/clients': clientList(pending, { figuresAt: null }) })
    const all = await rows()
    expect(cellsOf(all[1]!)).toEqual(['1001', 'Saltmarsh Shipping Ltd', '2', '', ''])
    expect(within(main()).getByText("Documents and Last Activity haven't been worked out since the server started. They show here within a few minutes.")).toBeTruthy()
    await expectNoAxeViolations(view.container)
  })

  it('sorts by each column, asking the server, ascending first and then the other way, and the address keeps the order', async () => {
    const { api, router } = await renderApp('/clients', LIST)
    const table = await grid()
    await rows()
    // The server's own order is by number, which the Number column says
    expect(within(table).getByRole('columnheader', { name: 'Number' }).getAttribute('aria-sort')).toBe('ascending')
    const sorts: Record<string, string> = { Name: 'name', Matters: 'matters', Documents: 'documents', 'Last Activity': 'lastActivity' }
    for (const [name, sort] of Object.entries(sorts)) {
      await userEvent.click(within(table).getByRole('columnheader', { name }))
      await waitFor(() => expect(lastQuery(api)).toEqual({ sort, order: 'asc', offset: 0, limit: 100 }))
      expect(router.state.location.search).toEqual({ sort, order: 'asc' })
    }
    await userEvent.click(within(table).getByRole('columnheader', { name: 'Last Activity' }))
    await waitFor(() => expect(lastQuery(api)).toEqual({ sort: 'lastActivity', order: 'desc', offset: 0, limit: 100 }))
    await waitFor(() => expect(within(table).getByRole('columnheader', { name: 'Last Activity' }).getAttribute('aria-sort')).toBe('descending'))
    await userEvent.click(within(table).getByRole('columnheader', { name: 'Number' }))
    await waitFor(() => expect(lastQuery(api)).toEqual({ sort: 'number', order: 'asc', offset: 0, limit: 100 }))
  })

  it("takes its order and words from the address, and leaves out what it doesn't know", async () => {
    const first = await renderApp('/clients?sort=matters&order=desc&q=salt&client=2', LIST)
    await grid()
    await waitFor(() => expect(lastQuery(first.api)).toEqual({ q: 'salt', sort: 'matters', order: 'desc', offset: 0, limit: 100 }))
    expect(within(filters()).getByRole('textbox', { name: 'Number or Name' })).toHaveProperty('value', 'salt')
    first.view.unmount()
    const { api: other } = await renderApp('/clients?sort=client&order=up&q=%20%20', LIST)
    await waitFor(() => expect(listCalls(other)).toHaveLength(1))
    expect(lastQuery(other)).toEqual({ offset: 0, limit: 100 })
  })

  it('filters by the number or name typed, once the typing pauses, and the address keeps it', async () => {
    const { api, router } = await renderApp('/clients', LIST)
    await rows()
    const field = within(filters()).getByRole('textbox', { name: 'Number or Name' })
    await userEvent.type(field, ' brig ')
    await pause()
    expect(lastQuery(api)).toEqual({ q: 'brig', offset: 0, limit: 100 })
    // One question for the words once they settled, not one for each key
    expect(listCalls(api)).toHaveLength(2)
    expect(router.state.location.search).toEqual({ q: 'brig' })
    expect(await within(await grid()).findByRole('row', { name: '1002 Brightwell Bakeries Ltd' })).toBeTruthy()
    await waitFor(() => expect(within(main()).getByText('1 Client')).toBeTruthy())
  })

  it('loads 100 rows at a time as the end of the list comes into view, to the end of what the server counted', async () => {
    // Sized, the list draws the rows in view, as in a browser: with none it would draw all 150, and each again at every page
    setListSize(1280, 680)
    const many = Array.from({ length: 150 }, (_, i) => clientOf({ id: 100 + i, number: 2000 + i, name: `Client ${String(i + 1).padStart(3, '0')}` }))
    const { api } = await renderApp('/clients', {
      'GET /v1/admin/clients': ({ query }: { query?: Record<string, unknown> }) => {
        const offset = Number(query?.offset ?? 0)
        return clientList(many.slice(offset, offset + Number(query?.limit)), { offset, limit: Number(query?.limit), total: many.length })
      },
    })
    const table = await grid()
    // How many rows the list holds, drawn or not: its heading row and the clients loaded
    const held = () => Number(table.getAttribute('aria-rowcount'))
    expect(await within(table).findByRole('row', { name: '2000 Client 001' })).toBeTruthy()
    expect(held()).toBe(1 + 100)
    act(() => bringObservedIntoView())
    await waitFor(() => expect(held()).toBe(1 + 150))
    act(() => bringObservedIntoView())
    await new Promise((resolve) => setTimeout(resolve, 100))
    expect(listCalls(api).map((call) => [call.query?.offset, call.query?.limit])).toEqual([
      [0, 100],
      [100, 100],
    ])
    // Scrolled past its end, where the list stops, it draws the last client the server counted
    table.scrollTop = Number.MAX_SAFE_INTEGER
    fireEvent.scroll(table)
    expect(await within(table).findByRole('row', { name: '2149 Client 150' })).toBeTruthy()
  })

  it("opens a client's page when its row is clicked, or Enter is pressed on it", async () => {
    const first = await renderApp('/clients', LIST)
    const table = await grid()
    await rows()
    await userEvent.click(within(table).getByRole('row', { name: /^1001 / }))
    await waitFor(() => expect(first.router.state.location.pathname).toBe('/clients/1'))
    first.view.unmount()

    const { router: other } = await renderApp('/clients', LIST)
    const again = await grid()
    await rows()
    const row = within(again).getByRole('row', { name: /^1003 / })
    act(() => row.focus())
    fireEvent.keyDown(row, { key: 'Enter' })
    fireEvent.keyUp(row, { key: 'Enter' })
    await waitFor(() => expect(other.state.location.pathname).toBe('/clients/3'))
  })

  it('says when no client has been made yet, and when none matches the filter', async () => {
    const { view } = await renderApp('/clients', { 'GET /v1/admin/clients': clientList([]) })
    expect(await within(main()).findByText('No client has been made yet.')).toBeTruthy()
    await expectNoAxeViolations(view.container)
    view.unmount()
    await renderApp('/clients?q=zzz', { 'GET /v1/admin/clients': clientList([]) })
    expect(await within(main()).findByText('No client matches the filter.')).toBeTruthy()
  })

  it("shows the catalogue's message when the list can't be had, with Try Again", async () => {
    let fail = true
    const { api } = await renderApp('/clients', { 'GET /v1/admin/clients': ({ query }: { query?: Record<string, unknown> }) => (fail ? problem(500, 'server-error') : clients(query)) })
    expect(await within(main()).findByText('Something went wrong.')).toBeTruthy()
    expect(within(main()).queryByText('A server sentence that must not be shown raw.')).toBeNull()
    fail = false
    await userEvent.click(within(main()).getByRole('button', { name: 'Try Again' }))
    await rows()
    expect(listCalls(api)).toHaveLength(2)
  })

  it("uses the firm's own word for a matter", async () => {
    setLabels({ matter: 'Project', matters: 'Projects' })
    await renderApp('/clients', LIST)
    const table = await grid()
    expect(within(table).getAllByRole('columnheader')[2]?.textContent).toBe('Projects')
  })
})

describe('New Client', () => {
  /** The list's New Client dialog, open, with the number the server suggests in it */
  async function openNewClient(routes = {}) {
    const rendered = await renderApp('/clients', { ...LIST, ...routes })
    await rows()
    await userEvent.click(within(main()).getByRole('button', { name: 'New Client' }))
    const dialog = await screen.findByRole('dialog', { name: 'New Client' })
    return { ...rendered, dialog }
  }
  const field = (dialog: HTMLElement, name: string) => within(dialog).getByRole('textbox', { name }) as HTMLInputElement
  const create = (dialog: HTMLElement) => within(dialog).getByRole('button', { name: 'Create Client' })

  it('suggests the next free number, says it never changes, and makes the client, then opens its page', async () => {
    let sent: unknown
    const made = clientOf({ id: 12, number: 1005, name: 'Marsh Lane Hall Trust' }, { matters: 0, documents: null, lastActivity: null })
    const { api, router, dialog, view } = await openNewClient({
      'POST /v1/admin/clients': ({ body }: { body?: unknown }) => {
        sent = body
        return made
      },
      'GET /v1/admin/clients/12': made,
    })
    await waitFor(() => expect(field(dialog, 'Number').value).toBe('1005'))
    expect(within(dialog).getByText("A client's number can't be changed once the client is made.")).toBeTruthy()
    // Nothing to make yet without a name
    expect(create(dialog).getAttribute('disabled')).not.toBeNull()
    await expectNoAxeViolations(view.baseElement)
    await userEvent.type(field(dialog, 'Name'), '  Marsh Lane Hall Trust ')
    await userEvent.click(create(dialog))
    await waitFor(() => expect(router.state.location.pathname).toBe('/clients/12'))
    expect(sent).toEqual({ number: 1005, name: 'Marsh Lane Hall Trust' })
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(await screen.findByText('Created 1005 – Marsh Lane Hall Trust')).toBeTruthy()
    expect(api.callsTo('GET /v1/admin/clients/next-number')).toHaveLength(1)
  })

  it('makes the client with a number of its own, typed in place of the one suggested', async () => {
    let sent: unknown
    const { router, dialog } = await openNewClient({
      'POST /v1/admin/clients': ({ body }: { body?: unknown }) => {
        sent = body
        return clientOf({ id: 13, number: 2001, name: 'Fenwick' })
      },
    })
    await waitFor(() => expect(field(dialog, 'Number').value).toBe('1005'))
    await userEvent.clear(field(dialog, 'Number'))
    await userEvent.type(field(dialog, 'Number'), '2001')
    await userEvent.type(field(dialog, 'Name'), 'Fenwick')
    await userEvent.keyboard('{Enter}')
    await waitFor(() => expect(router.state.location.pathname).toBe('/clients/13'))
    expect(sent).toEqual({ number: 2001, name: 'Fenwick' })
  })

  it("shows the rule's own sentence when the server refuses, and the dialog stays for another try", async () => {
    let tries = 0
    const { router, dialog, view } = await openNewClient({
      'POST /v1/admin/clients': () => {
        tries += 1
        return tries === 1 ? problem(409, 'refused', 'Client number 1005 is already used.') : clientOf({ id: 14, number: 1006, name: 'Fenwick' })
      },
    })
    await waitFor(() => expect(field(dialog, 'Number').value).toBe('1005'))
    await userEvent.type(field(dialog, 'Name'), 'Fenwick')
    await userEvent.click(create(dialog))
    const said = await within(dialog).findByRole('alert')
    expect(said.textContent).toBe('Client number 1005 is already used.')
    await expectNoAxeViolations(view.baseElement)
    // Changing the form takes the refusal away, and the next try goes through
    await userEvent.clear(field(dialog, 'Number'))
    await userEvent.type(field(dialog, 'Number'), '1006')
    expect(within(dialog).queryByRole('alert')).toBeNull()
    await userEvent.click(create(dialog))
    await waitFor(() => expect(router.state.location.pathname).toBe('/clients/14'))
  })

  it("says before Create Client that a number is a client's already, and whose", async () => {
    const { api, dialog } = await openNewClient()
    await waitFor(() => expect(field(dialog, 'Number').value).toBe('1005'))
    await userEvent.type(field(dialog, 'Name'), 'Fenwick')
    // A number other clients' numbers only start with is free
    await userEvent.clear(field(dialog, 'Number'))
    await userEvent.type(field(dialog, 'Number'), '100')
    await pause()
    expect(within(dialog).queryByText(/already used/)).toBeNull()
    expect(create(dialog).getAttribute('disabled')).toBeNull()
    await userEvent.type(field(dialog, 'Number'), '1')
    await pause()
    expect(await within(dialog).findByText('Client number 1001 is already used, by Saltmarsh Shipping Ltd.')).toBeTruthy()
    expect(create(dialog).getAttribute('disabled')).not.toBeNull()
    expect(field(dialog, 'Number').getAttribute('aria-invalid')).toBe('true')
    expect(api.callsTo('GET /v1/admin/clients').at(-1)?.query).toEqual({ q: '1001', limit: 20 })
    expect(api.callsTo('POST /v1/admin/clients')).toEqual([])
  })

  it("says a number has to be a whole number, 1 or more, before Create Client", async () => {
    const { dialog } = await openNewClient()
    await waitFor(() => expect(field(dialog, 'Number').value).toBe('1005'))
    await userEvent.type(field(dialog, 'Name'), 'Fenwick')
    for (const typed of ['0', '1.5', 'ten', '-3']) {
      await userEvent.clear(field(dialog, 'Number'))
      await userEvent.type(field(dialog, 'Number'), typed)
      expect(within(dialog).getByText('The number has to be a whole number, 1 or more.')).toBeTruthy()
      expect(create(dialog).getAttribute('disabled')).not.toBeNull()
    }
    // An emptied number is only missing: Create Client waits for one
    await userEvent.clear(field(dialog, 'Number'))
    expect(within(dialog).queryByText('The number has to be a whole number, 1 or more.')).toBeNull()
    expect(create(dialog).getAttribute('disabled')).not.toBeNull()
  })

  it('says a name over 255 characters is too long, before Create Client', async () => {
    const { dialog } = await openNewClient()
    await waitFor(() => expect(field(dialog, 'Number').value).toBe('1005'))
    fireEvent.change(field(dialog, 'Name'), { target: { value: 'x'.repeat(256) } })
    expect(within(dialog).getByText('That is too long.')).toBeTruthy()
    expect(create(dialog).getAttribute('disabled')).not.toBeNull()
    fireEvent.change(field(dialog, 'Name'), { target: { value: 'x'.repeat(255) } })
    expect(within(dialog).queryByText('That is too long.')).toBeNull()
    expect(create(dialog).getAttribute('disabled')).toBeNull()
  })

  it("shows a field the server says is wrong beside it, in the catalogue's words, and any other failure the catalogue's way", async () => {
    let answer: ApiError = new ApiError(422, 'validation', 'Server words.', { code: 'validation', fields: [{ field: 'name', code: 'required', detail: 'Server words.' }] })
    const { dialog } = await openNewClient({ 'POST /v1/admin/clients': () => answer })
    await waitFor(() => expect(field(dialog, 'Number').value).toBe('1005'))
    await userEvent.type(field(dialog, 'Name'), 'Fenwick')
    await userEvent.click(create(dialog))
    await waitFor(() => expect(field(dialog, 'Name').getAttribute('aria-invalid')).toBe('true'))
    expect(within(dialog).getByText('This needs a value.')).toBeTruthy()
    expect(within(dialog).queryByText('Server words.')).toBeNull()
    expect(within(dialog).queryByRole('alert')).toBeNull()

    answer = problem(500, 'server-error')
    await userEvent.type(field(dialog, 'Name'), ' Trust')
    await userEvent.click(create(dialog))
    expect((await within(dialog).findByRole('alert')).textContent).toBe('Something went wrong.')
    expect(within(dialog).queryByText('A server sentence that must not be shown raw.')).toBeNull()
  })

  it('leaves the Number empty for one to be typed when no number can be suggested', async () => {
    const { dialog } = await openNewClient({ 'GET /v1/admin/clients/next-number': problem(500, 'server-error') })
    await act(() => new Promise((resolve) => setTimeout(resolve, 20)))
    expect(field(dialog, 'Number').value).toBe('')
    await userEvent.type(field(dialog, 'Number'), '1005')
    await userEvent.type(field(dialog, 'Name'), 'Fenwick')
    expect(create(dialog).getAttribute('disabled')).toBeNull()
  })

  it('closes with Cancel, or Esc, making nothing, and the focus goes back to New Client', async () => {
    const { api, dialog } = await openNewClient()
    await userEvent.type(field(dialog, 'Name'), 'Fenwick')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(document.activeElement).toBe(within(main()).getByRole('button', { name: 'New Client' }))
    await userEvent.click(within(main()).getByRole('button', { name: 'New Client' }))
    const again = await screen.findByRole('dialog', { name: 'New Client' })
    // What was typed before is gone
    expect(field(again, 'Name').value).toBe('')
    await userEvent.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(api.callsTo('POST /v1/admin/clients')).toEqual([])
  })

  it('shows the new client in the list, which is asked again once one is made', async () => {
    let made = false
    const { api, dialog, router } = await openNewClient({
      'GET /v1/admin/clients': ({ query }: { query?: Record<string, unknown> }) =>
        made && query?.offset !== undefined ? clientList([...clientRows, clientOf({ id: 12, number: 1005, name: 'Fenwick' })]) : clients(query),
      'POST /v1/admin/clients': () => {
        made = true
        return clientOf({ id: 12, number: 1005, name: 'Fenwick' })
      },
    })
    const before = listCalls(api).length
    await userEvent.type(field(dialog, 'Name'), 'Fenwick')
    await waitFor(() => expect(field(dialog, 'Number').value).toBe('1005'))
    await userEvent.click(create(dialog))
    await waitFor(() => expect(router.state.location.pathname).toBe('/clients/12'))
    await act(() => router.navigate({ to: '/clients' }))
    expect(await within(await grid()).findByRole('row', { name: '1005 Fenwick' })).toBeTruthy()
    expect(listCalls(api).length).toBeGreaterThan(before)
  })
})
