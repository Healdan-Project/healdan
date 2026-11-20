// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { act, fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { formatDateTime, formatSize, setLabels } from '@healdan/ui'
import { expectNoAxeViolations } from '../test/axe'
import { clientOf, matterList, matterRows, problem, saltmarsh, type FakeApi } from '../test/fakeApi'
import { renderApp } from '../test/renderApp'

const main = () => screen.getByRole('main')
const pane = () => screen.getByRole('navigation', { name: 'Navigation' })
const grid = () => within(main()).findByRole('grid', { name: 'Matters' })
const cellsOf = (row: HTMLElement) => Array.from(row.querySelectorAll('[role="rowheader"], [role="gridcell"]'), (cell) => cell.textContent)
const lastQuery = (api: FakeApi) => api.callsTo('GET /v1/admin/matters').at(-1)?.query
/** The page's facts, each term with what it says */
const facts = () => {
  const terms = within(main()).getAllByRole('term')
  return terms.map((term) => [term.textContent, term.nextElementSibling?.textContent])
}

const saltmarshClient = clientOf(saltmarsh, { matters: 2, documents: 508, lastActivity: '2026-11-19T10:12:00Z' })

/** Client 1001's page: the client, and the matters the server lists for a clientId */
const PAGE = {
  'GET /v1/admin/clients/1': saltmarshClient,
  'GET /v1/admin/matters': ({ query }: { query?: Record<string, unknown> }) =>
    matterList(query?.clientId === undefined ? matterRows : matterRows.filter((one) => one.client.id === query.clientId)),
}

/** The matters' rows once the list has come, the header's left out */
async function rows() {
  const table = await grid()
  await within(table).findByRole('row', { name: /1001\.1/ })
  return within(table).getAllByRole('row').slice(1)
}

describe("A client's page", () => {
  it('shows the client, its facts and its matters, with the way back to Clients, which stays the open screen', async () => {
    const { api, view } = await renderApp('/clients/1', PAGE)
    expect(await screen.findByRole('heading', { level: 1, name: '1001 – Saltmarsh Shipping Ltd' })).toBeTruthy()
    expect(facts()).toEqual([
      ['Number', '1001'],
      ['Matters', '2'],
      ['Documents', '508'],
      ['Last Activity', formatDateTime('2026-11-19T10:12:00Z')],
    ])
    const crumbs = within(main()).getByRole('navigation', { name: 'Breadcrumb' })
    expect(within(crumbs).getByRole('link', { name: 'Clients' }).getAttribute('href')).toBe('/admin/clients')
    expect(within(pane()).getByRole('link', { name: 'Clients' }).getAttribute('aria-current')).toBe('page')

    // Its matters, through the Matters list's own route, without a Client column, which would say the same on every row
    const table = await grid()
    expect(within(table).getAllByRole('columnheader').map((one) => one.textContent)).toEqual([
      'Matter',
      'Name',
      'Store',
      'Owner',
      'Template',
      'Documents',
      'Size',
      'Last Activity',
    ])
    const all = await rows()
    expect(all.map((row) => cellsOf(row).slice(0, 2))).toEqual([
      ['1001.1', 'Charter Dispute – MV Northern Star'],
      ['1001.2', 'Vessel Sale'],
    ])
    expect(cellsOf(all[0]!).slice(2, 7)).toEqual(['Client Work', 'Tom Reeve', 'Litigation', '412', formatSize(matterRows[2]!.bytes!)])
    expect(api.callsTo('GET /v1/admin/matters').map((call) => call.query)).toEqual([{ clientId: 1, offset: 0, limit: 100 }])
    expect(within(main()).getByText('2 Matters')).toBeTruthy()
    expect(within(main()).getByText(`Documents, Size and Last Activity as of ${formatDateTime('2026-11-19T10:41:00Z')}`)).toBeTruthy()
    await expectNoAxeViolations(view.container)
  })

  it('goes back to the Clients list from its crumb', async () => {
    const { router } = await renderApp('/clients/1', PAGE)
    await screen.findByRole('heading', { level: 1, name: '1001 – Saltmarsh Shipping Ltd' })
    await userEvent.click(within(within(main()).getByRole('navigation', { name: 'Breadcrumb' })).getByRole('link', { name: 'Clients' }))
    await waitFor(() => expect(router.state.location.pathname).toBe('/clients'))
  })

  it('says the figures are still to come, before the server has worked them out since it started', async () => {
    const { view } = await renderApp('/clients/1', { ...PAGE, 'GET /v1/admin/clients/1': clientOf(saltmarsh, { documents: null, lastActivity: null }) })
    await screen.findByRole('heading', { level: 1, name: '1001 – Saltmarsh Shipping Ltd' })
    expect(facts().slice(2)).toEqual([
      ['Documents', 'Not worked out yet'],
      ['Last Activity', 'Not worked out yet'],
    ])
    await expectNoAxeViolations(view.container)
  })

  it('says a client with nothing filed has had no activity', async () => {
    await renderApp('/clients/1', { ...PAGE, 'GET /v1/admin/clients/1': clientOf(saltmarsh, { matters: 0, documents: 0, lastActivity: null }), 'GET /v1/admin/matters': matterList([]) })
    await screen.findByRole('heading', { level: 1, name: '1001 – Saltmarsh Shipping Ltd' })
    expect(facts().slice(1)).toEqual([
      ['Matters', '0'],
      ['Documents', '0'],
      ['Last Activity', 'None'],
    ])
    expect(await within(main()).findByText('No matter has been made for this client yet.')).toBeTruthy()
  })

  it('sorts its matters by any column but Client, asking the server, and the address keeps the order', async () => {
    const { api, router } = await renderApp('/clients/1', PAGE)
    const table = await grid()
    await rows()
    expect(within(table).getByRole('columnheader', { name: 'Matter' }).getAttribute('aria-sort')).toBe('ascending')
    await userEvent.click(within(table).getByRole('columnheader', { name: 'Documents' }))
    await waitFor(() => expect(lastQuery(api)).toEqual({ clientId: 1, sort: 'documents', order: 'asc', offset: 0, limit: 100 }))
    expect(router.state.location.search).toEqual({ sort: 'documents', order: 'asc' })
    await userEvent.click(within(table).getByRole('columnheader', { name: 'Documents' }))
    await waitFor(() => expect(lastQuery(api)).toEqual({ clientId: 1, sort: 'documents', order: 'desc', offset: 0, limit: 100 }))
    expect(router.state.location.pathname).toBe('/clients/1')
  })

  it("takes its matters' order from the address, and leaves out what it doesn't know", async () => {
    const first = await renderApp('/clients/1?sort=owner&order=desc', PAGE)
    await waitFor(() => expect(lastQuery(first.api)).toEqual({ clientId: 1, sort: 'owner', order: 'desc', offset: 0, limit: 100 }))
    first.view.unmount()
    // Every matter here is this client's, so there's no sorting by client
    const { api } = await renderApp('/clients/1?sort=client&order=sideways&clientId=2', PAGE)
    await waitFor(() => expect(api.callsTo('GET /v1/admin/matters')).toHaveLength(1))
    expect(lastQuery(api)).toEqual({ clientId: 1, offset: 0, limit: 100 })
  })

  it("opens a matter's page when its row is clicked, or Enter is pressed on it", async () => {
    const first = await renderApp('/clients/1', PAGE)
    const table = await grid()
    await rows()
    await userEvent.click(within(table).getByRole('row', { name: /1001\.2/ }))
    await waitFor(() => expect(first.router.state.location.pathname).toBe('/matters/12'))
    first.view.unmount()

    const { router } = await renderApp('/clients/1', PAGE)
    await rows()
    const row = within(await grid()).getByRole('row', { name: /1001\.1/ })
    act(() => row.focus())
    fireEvent.keyDown(row, { key: 'Enter' })
    fireEvent.keyUp(row, { key: 'Enter' })
    await waitFor(() => expect(router.state.location.pathname).toBe('/matters/11'))
  })

  it("says there's no such client when the server has none, and when the address names none, with the way to Clients", async () => {
    const { api, view } = await renderApp('/clients/77', PAGE)
    expect(await screen.findByRole('heading', { level: 1, name: 'Client Not Found' })).toBeTruthy()
    expect(within(main()).getByText("There's no such client.")).toBeTruthy()
    expect(within(main()).getByRole('link', { name: 'Go to Clients' }).getAttribute('href')).toBe('/admin/clients')
    expect(api.callsTo('GET /v1/admin/matters')).toEqual([])
    await expectNoAxeViolations(view.container)
    view.unmount()

    const other = await renderApp('/clients/one', PAGE)
    expect(await screen.findByRole('heading', { level: 1, name: 'Client Not Found' })).toBeTruthy()
    expect(other.api.callsTo('GET /v1/admin/clients/*')).toEqual([])
  })

  it("shows the catalogue's message when the client can't be had, with Try Again", async () => {
    let fail = true
    const { api } = await renderApp('/clients/1', { ...PAGE, 'GET /v1/admin/clients/1': () => (fail ? problem(500, 'server-error') : saltmarshClient) })
    expect(await within(main()).findByText('Something went wrong.')).toBeTruthy()
    expect(within(main()).queryByText('A server sentence that must not be shown raw.')).toBeNull()
    fail = false
    await userEvent.click(within(main()).getByRole('button', { name: 'Try Again' }))
    expect(await screen.findByRole('heading', { level: 1, name: '1001 – Saltmarsh Shipping Ltd' })).toBeTruthy()
    expect(api.callsTo('GET /v1/admin/clients/1')).toHaveLength(2)
  })

  it("uses the firm's own word for a matter", async () => {
    setLabels({ matter: 'Project', matters: 'Projects' })
    await renderApp('/clients/1', PAGE)
    expect(await within(main()).findByRole('grid', { name: 'Projects' })).toBeTruthy()
    expect(facts()[1]).toEqual(['Projects', '2'])
  })
})

describe('Renaming a client', () => {
  async function openRename(routes = {}) {
    const rendered = await renderApp('/clients/1', { ...PAGE, ...routes })
    await screen.findByRole('heading', { level: 1, name: '1001 – Saltmarsh Shipping Ltd' })
    await userEvent.click(within(main()).getByRole('button', { name: 'Rename' }))
    const dialog = await screen.findByRole('dialog', { name: 'Rename Client' })
    return { ...rendered, dialog }
  }
  const name = (dialog: HTMLElement) => within(dialog).getByRole('textbox', { name: 'Name' }) as HTMLInputElement
  const rename = (dialog: HTMLElement) => within(dialog).getByRole('button', { name: 'Rename' })

  it('changes only the name: the number shows, and is no field', async () => {
    const renamed = { ...saltmarshClient, name: 'Saltmarsh Shipping Group' }
    let sent: unknown
    const { api, dialog, view } = await openRename({
      'PATCH /v1/admin/clients/1': ({ body }: { body?: unknown }) => {
        sent = body
        return renamed
      },
    })
    expect(name(dialog).value).toBe('Saltmarsh Shipping Ltd')
    expect(within(dialog).getByText("Its number, 1001, can't be changed.")).toBeTruthy()
    expect(within(dialog).queryByRole('textbox', { name: 'Number' })).toBeNull()
    // The same name is no change
    expect(rename(dialog).getAttribute('disabled')).not.toBeNull()
    await expectNoAxeViolations(view.baseElement)
    const before = api.callsTo('GET /v1/admin/matters').length
    await userEvent.clear(name(dialog))
    await userEvent.type(name(dialog), ' Saltmarsh Shipping Group ')
    await userEvent.click(rename(dialog))
    expect(await screen.findByRole('heading', { level: 1, name: '1001 – Saltmarsh Shipping Group' })).toBeTruthy()
    expect(sent).toEqual({ name: 'Saltmarsh Shipping Group' })
    expect(screen.queryByRole('dialog')).toBeNull()
    // Its matters' rows name the client, so they're asked again
    await waitFor(() => expect(api.callsTo('GET /v1/admin/matters').length).toBeGreaterThan(before))
    expect(document.activeElement).toBe(within(main()).getByRole('button', { name: 'Rename' }))
  })

  it("shows the rule's own sentence when the server refuses, and the dialog stays", async () => {
    const { dialog, view } = await openRename({ 'PATCH /v1/admin/clients/1': problem(409, 'refused', 'The client name is too long.') })
    await userEvent.type(name(dialog), ' Group')
    await userEvent.click(rename(dialog))
    expect((await within(dialog).findByRole('alert')).textContent).toBe('The client name is too long.')
    // The page behind the dialog, which the dialog hides from a screen reader while it's open, keeps the old name
    expect(screen.getByRole('heading', { level: 1, name: '1001 – Saltmarsh Shipping Ltd', hidden: true })).toBeTruthy()
    await expectNoAxeViolations(view.baseElement)
  })

  it('takes a blank name for no name, and changes nothing on Cancel', async () => {
    const { api, dialog } = await openRename()
    await userEvent.clear(name(dialog))
    await userEvent.type(name(dialog), '   ')
    expect(rename(dialog).getAttribute('disabled')).not.toBeNull()
    await userEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(api.callsTo('PATCH /v1/admin/clients/*')).toEqual([])
    expect(screen.getByRole('heading', { level: 1, name: '1001 – Saltmarsh Shipping Ltd' })).toBeTruthy()
  })
})
