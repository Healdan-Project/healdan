// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import type { adminComponents } from '@healdan/api'
import { formatDate, formatDateTime, formatSize } from '@healdan/ui'
import { expectNoAxeViolations } from '../test/axe'
import { clientWorkInfo, deletedStoreOf, firmInfo, problem, regionalOffice, storeInfoOf, stores } from '../test/fakeApi'
import { renderApp } from '../test/renderApp'

type A = adminComponents['schemas']

const main = () => screen.getByRole('main')
const pane = () => screen.getByRole('navigation', { name: 'Navigation' })
const cellsOf = (row: HTMLElement) => Array.from(row.querySelectorAll('[role="rowheader"], [role="gridcell"]'), (cell) => cell.textContent)
const MB = 1024 ** 2

/** The Stores' rows once the list has come, the header's left out */
async function rows() {
  const table = await within(main()).findByRole('grid', { name: 'Stores' })
  await within(table).findByRole('row', { name: /Client Work/ })
  return within(table).getAllByRole('row').slice(1)
}

/** The Deleted Stores' rows, each cell's text, the header's left out */
async function deletedRows() {
  const section = await within(main()).findByRole('region', { name: 'Deleted Stores' })
  const table = await within(section).findByRole('table')
  return within(table)
    .getAllByRole('row')
    .slice(1)
    .map((row) => within(row).getAllByRole('cell').map((cell) => cell.textContent))
}

describe('The Stores list', () => {
  it('shows each Store with its backend, matters, figures, who can read it and its files, in the order the server gives', async () => {
    const { view } = await renderApp('/stores')
    expect(await screen.findByRole('heading', { level: 1, name: 'Stores' })).toBeTruthy()
    const table = await within(main()).findByRole('grid', { name: 'Stores' })
    expect(within(table).getAllByRole('columnheader').map((one) => one.textContent)).toEqual(['Store', 'Backend', 'Matters', 'Documents', 'Size', 'Who Can Read', 'Files'])
    expect((await rows()).map(cellsOf)).toEqual([
      ['Client Work', 'Wasabi EU (Encrypted)', '6', '1,004', formatSize(clientWorkInfo.bytes!), 'Everyone', '3 files stuck'],
      ['Firm', 'Original', '2', '280', formatSize(firmInfo.bytes!), 'Everyone, Helen Ashcombe', 'Up to Date'],
    ])
    expect(within(main()).getByText('2 Stores')).toBeTruthy()
    expect(within(main()).getByText(`Documents and Size as of ${formatDateTime('2026-11-19T10:41:00Z')}`)).toBeTruthy()
    expect(within(main()).getByRole('button', { name: 'New Store' })).toBeTruthy()
    // Stores is the open screen, before Backends in the left pane's Storage group
    const storage = within(pane()).getByRole('region', { name: 'Storage' })
    expect(within(storage).getAllByRole('link').map((link) => link.textContent)).toEqual(['Stores', 'Backends'])
    expect(within(pane()).getByRole('link', { name: 'Stores' }).getAttribute('aria-current')).toBe('page')
    await expectNoAxeViolations(view.container)
  })

  it('says how many files wait to move, and that only administrators can read a Store nobody else can', async () => {
    const waiting = storeInfoOf(regionalOffice, { backend: clientWorkInfo.backend, pending: { waiting: 188, waitingBytes: Math.round(11.3 * MB), stuck: 0 } })
    const both = storeInfoOf({ id: 7, name: 'Personal' }, { pending: { waiting: 12, waitingBytes: MB, stuck: 1 } })
    await renderApp('/stores', { 'GET /v1/admin/stores': [clientWorkInfo, both, waiting] })
    const all = await rows()
    expect(cellsOf(all[1]!).slice(5)).toEqual(['Administrators Only', '1 file stuck, 12 files waiting'])
    expect(cellsOf(all[2]!).slice(5)).toEqual(['Administrators Only', `188 files waiting (${formatSize(Math.round(11.3 * MB))})`])
  })

  it("says the figures are still to come before the server has worked them out, with the Stores' own columns blank", async () => {
    await renderApp('/stores', { 'GET /v1/admin/stores': stores.map((store) => ({ ...store, documents: null, bytes: null, figuresAt: null })) })
    const all = await rows()
    expect(cellsOf(all[0]!).slice(2, 5)).toEqual(['6', '', ''])
    expect(within(main()).getByText("Documents and Size haven't been worked out since the server started. They show here within a few minutes.")).toBeTruthy()
  })

  it("leaves out the backend and the files on a server that doesn't use storage backends, and says so", async () => {
    const { view } = await renderApp('/stores', { 'GET /v1/admin/stores': stores.map((store) => ({ ...store, backend: null, pending: null })) })
    const table = await within(main()).findByRole('grid', { name: 'Stores' })
    await rows()
    expect(within(table).getAllByRole('columnheader').map((one) => one.textContent)).toEqual(['Store', 'Matters', 'Documents', 'Size', 'Who Can Read'])
    expect(within(main()).getByText("Storage backends aren't in use on this server, so every Store keeps its files in LogicalDOC's own store.")).toBeTruthy()
    await expectNoAxeViolations(view.container)
  })

  it("opens a Store's page from its row", async () => {
    const { router } = await renderApp('/stores')
    const all = await rows()
    await userEvent.click(within(all[1]!).getByRole('rowheader'))
    await waitFor(() => expect(router.state.location.pathname).toBe('/stores/3'))
  })

  it('asks for the Stores again when Refresh is pressed, and when the person comes back to the window', async () => {
    const { api } = await renderApp('/stores')
    await rows()
    expect(api.callsTo('GET /v1/admin/stores')).toHaveLength(1)
    await userEvent.click(within(main()).getByRole('button', { name: 'Refresh' }))
    await waitFor(() => expect(api.callsTo('GET /v1/admin/stores')).toHaveLength(2))
    await waitFor(() => expect(api.callsTo('GET /v1/admin/stores/deleted')).toHaveLength(2))
    await act(async () => void window.dispatchEvent(new Event('focus')))
    await waitFor(() => expect(api.callsTo('GET /v1/admin/stores')).toHaveLength(3))
  })

  it('makes a Store with New Store, asks for the list again and opens its page', async () => {
    let made = false
    const { api, router } = await renderApp('/stores', {
      'POST /v1/admin/stores': () => {
        made = true
        return regionalOffice
      },
      'GET /v1/admin/stores': () => (made ? [...stores, storeInfoOf(regionalOffice)] : stores),
    })
    await rows()
    await userEvent.click(within(main()).getByRole('button', { name: 'New Store' }))
    const dialog = await screen.findByRole('dialog', { name: 'New Store' })
    await userEvent.type(within(dialog).getByRole('textbox', { name: 'Name' }), 'Regional Office')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Create Store' }))
    await waitFor(() => expect(router.state.location.pathname).toBe('/stores/5'))
    await waitFor(() => expect(api.callsTo('GET /v1/admin/stores').length).toBeGreaterThanOrEqual(2))
  })

  it('says so when there is no Store yet', async () => {
    await renderApp('/stores', { 'GET /v1/admin/stores': [] })
    expect(await within(main()).findByText('No Store has been made yet.')).toBeTruthy()
  })

  it('offers to try again when the Stores fail to load', async () => {
    await renderApp('/stores', { 'GET /v1/admin/stores': () => problem(500, 'server-error') })
    expect(await within(main()).findByRole('button', { name: 'Try Again' })).toBeTruthy()
  })
})

describe('The deleted Stores', () => {
  const removed = deletedStoreOf({ id: 7, name: 'Archive 2019', state: 'removed', matters: 0, deletedBy: null, deletedAt: '2026-10-01T09:00:00Z', removedAt: '2026-10-02T09:00:00Z', destroyAfter: '2026-11-01T02:47:00Z' })

  it('says none is in the Trash', async () => {
    await renderApp('/stores')
    const section = await within(main()).findByRole('region', { name: 'Deleted Stores' })
    expect(await within(section).findByText('No Store is in the Trash.')).toBeTruthy()
    expect(within(section).queryByRole('button')).toBeNull()
  })

  it('lists each with when and by whom it was deleted, the matters it took, and whether it was removed from the Trash', async () => {
    const { view } = await renderApp('/stores', { 'GET /v1/admin/stores/deleted': [deletedStoreOf(), removed] })
    const section = await within(main()).findByRole('region', { name: 'Deleted Stores' })
    const table = await within(section).findByRole('table')
    expect(within(table).getAllByRole('columnheader').map((one) => one.textContent)).toEqual(['Store', 'Deleted', 'Deleted By', 'Matters', 'Where It Is', 'Restore'])
    expect(await deletedRows()).toEqual([
      ['Old Branch', formatDateTime('2026-11-18T15:00:00Z'), 'Sam Okafor', '2', 'In the Trash', 'Restore'],
      ['Archive 2019', formatDateTime('2026-10-01T09:00:00Z'), 'Not Known', '0', `Removed from the Trash; what's in it is destroyed for good after ${formatDate('2026-11-01T02:47:00Z')}`, 'Restore'],
    ])
    expect(within(section).getByRole('button', { name: 'Restore Old Branch' })).toBeTruthy()
    await expectNoAxeViolations(view.container)
  })

  it('restores one after asking, naming what comes back, and asks for both lists again', async () => {
    let restored = false
    const back = storeInfoOf({ id: 6, name: 'Old Branch' }, { matters: 2 })
    const { api, view } = await renderApp('/stores', {
      'GET /v1/admin/stores/deleted': () => (restored ? [] : [deletedStoreOf()]),
      'GET /v1/admin/stores': () => (restored ? [...stores, back] : stores),
      'POST /v1/admin/stores/6/restore': () => {
        restored = true
        return back satisfies A['StoreInfo']
      },
    })
    const section = await within(main()).findByRole('region', { name: 'Deleted Stores' })
    await userEvent.click(await within(section).findByRole('button', { name: 'Restore Old Branch' }))
    const question = await screen.findByRole('alertdialog', { name: 'Restore Old Branch?' })
    expect(within(question).getByText('Old Branch comes back under the root, with its 2 matters and everything else its delete took.')).toBeTruthy()
    expect(within(question).getByText('Anything deleted on its own before the Store was stays in the Trash.')).toBeTruthy()
    // Nothing is sent until it's answered
    expect(api.callsTo('POST /v1/admin/stores/6/restore')).toEqual([])
    await expectNoAxeViolations(view.baseElement)
    await userEvent.click(within(question).getByRole('button', { name: 'Restore' }))
    await waitFor(() => expect(screen.queryByRole('alertdialog', { name: 'Restore Old Branch?' })).toBeNull())
    expect(api.callsTo('POST /v1/admin/stores/6/restore')).toHaveLength(1)
    expect(await screen.findByText('Restored Old Branch')).toBeTruthy()
    expect(await within(section).findByText('No Store is in the Trash.')).toBeTruthy()
    expect(await within(main()).findByRole('row', { name: /Old Branch/ })).toBeTruthy()
  })

  it('puts the focus on the next Restore once a Store is restored, and on the heading once none is left after it', async () => {
    let left = [deletedStoreOf(), removed]
    const restore = (id: number, name: string) => () => {
      left = left.filter((one) => one.id !== id)
      return storeInfoOf({ id, name }) satisfies A['StoreInfo']
    }
    await renderApp('/stores', {
      'GET /v1/admin/stores/deleted': () => left,
      'POST /v1/admin/stores/6/restore': restore(6, 'Old Branch'),
      'POST /v1/admin/stores/7/restore': restore(7, 'Archive 2019'),
    })
    const section = await within(main()).findByRole('region', { name: 'Deleted Stores' })
    await userEvent.click(await within(section).findByRole('button', { name: 'Restore Old Branch' }))
    await userEvent.click(within(await screen.findByRole('alertdialog', { name: 'Restore Old Branch?' })).getByRole('button', { name: 'Restore' }))
    await waitFor(() => expect(within(section).queryByRole('button', { name: 'Restore Old Branch' })).toBeNull())
    await waitFor(() => expect(document.activeElement).toBe(within(section).getByRole('button', { name: 'Restore Archive 2019' })))

    await userEvent.click(within(section).getByRole('button', { name: 'Restore Archive 2019' }))
    await userEvent.click(within(await screen.findByRole('alertdialog', { name: 'Restore Archive 2019?' })).getByRole('button', { name: 'Restore' }))
    expect(await within(section).findByText('No Store is in the Trash.')).toBeTruthy()
    await waitFor(() => expect(document.activeElement).toBe(within(section).getByRole('heading', { level: 2, name: 'Deleted Stores' })))
  })

  it("says what comes back with a Store that took no matter, and a removed one's restore", async () => {
    await renderApp('/stores', { 'GET /v1/admin/stores/deleted': [removed] })
    const section = await within(main()).findByRole('region', { name: 'Deleted Stores' })
    await userEvent.click(await within(section).findByRole('button', { name: 'Restore Archive 2019' }))
    const question = await screen.findByRole('alertdialog', { name: 'Restore Archive 2019?' })
    expect(within(question).getByText('Archive 2019 comes back under the root, with everything its delete took.')).toBeTruthy()
  })

  it("says a refusal in the rule's words, as when another administrator restored it first, and asks for the lists again", async () => {
    const { api } = await renderApp('/stores', {
      'GET /v1/admin/stores/deleted': [deletedStoreOf()],
      'POST /v1/admin/stores/6/restore': () => problem(409, 'refused', "Old Branch isn't in the Trash."),
    })
    const section = await within(main()).findByRole('region', { name: 'Deleted Stores' })
    await userEvent.click(await within(section).findByRole('button', { name: 'Restore Old Branch' }))
    const question = await screen.findByRole('alertdialog', { name: 'Restore Old Branch?' })
    await userEvent.click(within(question).getByRole('button', { name: 'Restore' }))
    expect((await within(question).findByRole('alert')).textContent).toBe("Old Branch isn't in the Trash.")
    await waitFor(() => expect(api.callsTo('GET /v1/admin/stores/deleted').length).toBeGreaterThanOrEqual(2))
    await waitFor(() => expect(api.callsTo('GET /v1/admin/stores').length).toBeGreaterThanOrEqual(2))
  })
})

describe("New Store's backend", () => {
  it('is offered on the Stores list, as in New Matter', async () => {
    await renderApp('/stores')
    await rows()
    await userEvent.click(within(main()).getByRole('button', { name: 'New Store' }))
    const dialog = await screen.findByRole('dialog', { name: 'New Store' })
    const backend = (await within(dialog).findByRole('combobox', { name: 'Backend' })) as HTMLSelectElement
    expect(backend.selectedOptions[0]?.textContent).toBe('Original')
  })
})

