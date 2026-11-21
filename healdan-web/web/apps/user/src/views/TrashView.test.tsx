// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { act, fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import type { components } from '@healdan/api'
import { formatDate, formatDateTime } from '@healdan/ui'
import { expectNoAxeViolations } from '../test/axe'
import { resizeByKeys, sliderOf } from '../test/columns'
import { client, folder, me, problem, trashItem, type Routes } from '../test/fakeApi'
import { renderApp } from '../test/renderApp'

type S = components['schemas']

// The Trash (folders and Trash spec 7.1 to 7.4): what the person deleted, restored back where it was or removed to Removed,
// and, for an administrator, everyone's and what was removed.

const store: S['FolderRef'] = { id: 2, name: 'Smoke', kind: 'store' }
const workspace: S['FolderRef'] = { id: 110, name: '1001.1 – Charter Dispute', kind: 'matter' }
const draftsPlace = folder({ id: 8, name: 'Drafts', path: [store, workspace, { id: 8, name: 'Drafts', kind: 'folder' }] })
const correspondencePlace = folder({ id: 111, name: 'Correspondence', path: [store, workspace, { id: 111, name: 'Correspondence', kind: 'folder' }] })
const matterTop = folder({ id: 110, name: '1001.1 – Charter Dispute', kind: 'matter', path: [store, workspace], canFile: false, canRename: false, canMove: false, canDelete: false })

const oldDrafts = trashItem({
  id: 701,
  type: 'folder',
  name: 'Old Drafts',
  docNumber: null,
  kind: 'folder',
  size: null,
  contents: { folders: 3, documents: 11 },
  deletedFrom: { folder: { id: 8, name: 'Drafts', kind: 'folder' }, path: ['Smoke', '1001.1 – Charter Dispute', 'Drafts'] },
  deletedAt: '2026-11-17T10:42:00Z',
})
const harbourLetter = trashItem({
  id: 702,
  name: 'Letter to Harbour Master v2.docx',
  docNumber: 10412,
  deletedFrom: { folder: { id: 111, name: 'Correspondence', kind: 'folder' }, path: ['Smoke', '1001.1 – Charter Dispute', 'Correspondence'] },
  deletedAt: '2026-11-17T09:15:00Z',
})
const rentEmail = trashItem({
  id: 703,
  name: 'RE Rent review schedule.eml',
  docNumber: 10388,
  matter: { id: 12, display: '1002.1 – Commercial Lease', client: client({ id: 2, number: 1002, name: 'Brightwell' }) },
  // The folder it was deleted from is deleted too: the path is the one recorded
  deletedFrom: { folder: null, path: ['Smoke', '1002.1 – Commercial Lease', 'Emails'] },
  deletedAt: '2026-11-16T16:20:00Z',
})
const mine = [oldDrafts, harbourLetter, rentEmail]

const DAY = 24 * 60 * 60 * 1000
const inDays = (days: number) => new Date(Date.now() + days * DAY).toISOString()

// Removed: one whose remover the upgrade didn't record, and one that goes in two days
const removedRent = trashItem({
  id: 704,
  name: 'Rent schedule 2027.xlsx',
  docNumber: 10377,
  deletedBy: { id: 7, name: 'Chris Doyle' },
  removedBy: { id: 7, name: 'Chris Doyle' },
  removedAt: '2026-11-09T08:00:00Z',
  destroyAfter: inDays(23),
})
const removedWitness = trashItem({
  id: 705,
  name: 'Witness statement – draft 1.docx',
  docNumber: 10240,
  deletedBy: { id: 8, name: 'Priya Nair' },
  removedBy: null,
  removedAt: '2026-10-19T08:00:00Z',
  destroyAfter: inDays(2),
})

const admin: S['UserView'] = { ...me, admin: true }

function trashPage(items: S['TrashItem'][], removedDays = 30): S['TrashPage'] {
  return { items, offset: 0, limit: 100, total: items.length, removedDays }
}

/** The Trash's routes: each view answers its own items, and restores answer the place each goes back to */
function routes(over: Routes = {}, views: Partial<Record<string, S['TrashItem'][]>> = {}): Routes {
  const lists: Record<string, S['TrashItem'][]> = { mine, everyone: [...mine, { ...harbourLetter, id: 706, deletedBy: { id: 7, name: 'Chris Doyle' } }], removed: [removedRent, removedWitness], ...views }
  return {
    'GET /v1/trash': ({ query }: { query?: Record<string, unknown> }) => trashPage(lists[String(query?.view ?? 'mine')] ?? []),
    'POST /v1/trash/*/restore': ({ params }: { params: string[] }) => {
      const item = [...mine, removedRent, removedWitness].find((one) => one.id === Number(params[0]))!
      return { place: item.type === 'folder' ? draftsPlace : correspondencePlace, item }
    },
    'DELETE /v1/trash/*': undefined,
    'DELETE /v1/trash': undefined,
    'GET /v1/folders/110': matterTop,
    'GET /v1/folders/111': correspondencePlace,
    ...over,
  }
}

const main = () => screen.getByRole('main')
const grid = () => within(main()).findByRole('grid')
const row = async (name: RegExp) => within(await grid()).findByRole('row', { name })
const dataRows = async () => within(await grid()).getAllByRole('row').slice(1)
const headers = async () => within(await grid()).getAllByRole('columnheader').map((header) => header.textContent)
const toolbar = () => within(main()).findByRole('toolbar', { name: 'Trash Actions' })
const cells = (one: HTMLElement) => Array.from(one.querySelectorAll('[role="rowheader"], [role="gridcell"]'), (cell) => cell.textContent)
const details = () => screen.findByRole('region', { name: 'Details' })
const restoreCalls = (api: { callsTo: (pattern: string) => unknown[] }) => api.callsTo('POST /v1/trash/*/restore')

/** Focuses a row and selects it with Space, then extends the selection to the next rows with Shift+Down: no click */
async function selectRows(first: RegExp, more = 0) {
  const target = await row(first)
  act(() => target.focus())
  await userEvent.keyboard(' ')
  for (let i = 0; i < more; i++) await userEvent.keyboard('{Shift>}{ArrowDown}{/Shift}')
}

describe('The Trash', () => {
  it('lists what the person deleted, newest first, with Name, Doc #, Deleted From and Deleted', async () => {
    const { api } = await renderApp('/trash', routes())
    expect(await within(main()).findByRole('heading', { level: 1, name: 'Trash' })).toBeTruthy()
    await row(/Old Drafts/)
    expect(await headers()).toEqual(['Name', 'Doc #', 'Deleted From', 'Deleted'])
    const rows = await dataRows()
    expect(rows.map((one) => one.getAttribute('data-row-key'))).toEqual(['t701', 't702', 't703'])
    // A folder is one row, with how many items it holds beside its name; its contents aren't rows of their own
    const [drafts, letter, email] = rows.map(cells)
    expect(drafts).toEqual(['Old Drafts14 items', '', '1001.1 › Drafts', formatDate(oldDrafts.deletedAt)])
    expect(letter).toEqual(['Letter to Harbour Master v2.docx', '10412', '1001.1 › Correspondence', formatDate(harbourLetter.deletedAt)])
    // The path recorded at the delete, when that folder isn't there any more
    expect(email![2]).toBe('1002.1 › Emails')
    expect(within(rows[0]!).getByText('14 items')).toBeTruthy()
    expect(rows[0]!.querySelector('svg')!.getAttribute('data-kind')).toBe('folder')
    // Newest first, as the server gives them, and the list says so
    expect(within(await grid()).getByRole('columnheader', { name: /Deleted$/ }).getAttribute('aria-sort')).toBe('descending')
    expect(api.callsTo('GET /v1/trash')[0]?.query).toEqual({ view: 'mine', offset: 0, limit: 100 })
    expect(within(await toolbar()).getByText('3 items')).toBeTruthy()
    await expectNoAxeViolations(main())
  })

  it('sorts by Name, asking the server, and keeps the sort in the address', async () => {
    const { api, router } = await renderApp('/trash', routes())
    await row(/Old Drafts/)
    fireEvent.click(within(await grid()).getByRole('columnheader', { name: /Name/ }))
    await waitFor(() => expect(router.state.location.search).toMatchObject({ sort: 'name', order: 'asc' }))
    await waitFor(() => expect(api.callsTo('GET /v1/trash').at(-1)?.query).toEqual({ view: 'mine', sort: 'name', order: 'asc', offset: 0, limit: 100 }))
    // Doc # and Deleted From don't sort
    expect(within(await grid()).getByRole('columnheader', { name: /Doc #/ }).hasAttribute('aria-sort')).toBe(false)
    expect(within(await grid()).getByRole('columnheader', { name: /Deleted From/ }).hasAttribute('aria-sort')).toBe(false)
  })

  it("sorts Deleted newest first at its first click, as the server's own order is", async () => {
    const { router } = await renderApp('/trash?sort=name&order=asc', routes())
    await row(/Old Drafts/)
    fireEvent.click(within(await grid()).getByRole('columnheader', { name: /Deleted$/ }))
    await waitFor(() => expect(router.state.location.search).toMatchObject({ sort: 'deleted', order: 'desc' }))
  })

  it("the Trash's columns resize, and keep their sorts", async () => {
    localStorage.clear()
    const { api, router } = await renderApp('/trash', routes({ 'GET /v1/me': admin }))
    await row(/Old Drafts/)
    const heading = async (name: RegExp) => within(await grid()).getByRole('columnheader', { name })
    // Every heading carries a resizer
    for (const one of within(await grid()).getAllByRole('columnheader')) expect(sliderOf(one)).toBeTruthy()
    // A kept width is drawn on its column
    localStorage.setItem('healdan.columns.trashMine', JSON.stringify({ v: 1, widths: { docNumber: 140 } }))
    await userEvent.click(within(await within(main()).findByRole('radiogroup', { name: 'Show' })).getByRole('radio', { name: "Everyone's" }))
    await waitFor(async () => expect(await headers()).toContain('Deleted By'))
    await userEvent.click(within(main()).getByRole('radio', { name: 'Mine' }))
    await waitFor(async () => expect((await heading(/Doc #/)).style.width).toBe('140px'))
    // A resize by keys ends with the width kept under this view's name only
    await resizeByKeys(await heading(/Doc #/), ['ArrowRight', 'Enter'])
    await waitFor(() => expect(JSON.parse(localStorage.getItem('healdan.columns.trashMine')!).widths.docNumber).toBe(150))
    expect(localStorage.getItem('healdan.columns.trashEveryone')).toBeNull()
    expect(localStorage.getItem('healdan.columns.trashRemoved')).toBeNull()
    // Reset Columns, from a heading's menu, forgets it
    fireEvent.contextMenu(await heading(/Doc #/))
    await userEvent.click(await screen.findByRole('menuitem', { name: 'Reset Columns' }))
    await waitFor(() => expect(localStorage.getItem('healdan.columns.trashMine')).toBeNull())
    // The sorts still go to the server
    fireEvent.click(await heading(/Name/))
    await waitFor(() => expect(router.state.location.search).toMatchObject({ sort: 'name', order: 'asc' }))
    await waitFor(() => expect(api.callsTo('GET /v1/trash').at(-1)?.query).toEqual({ view: 'mine', sort: 'name', order: 'asc', offset: 0, limit: 100 }))
    // Removed keeps its own widths
    await userEvent.click(within(main()).getByRole('radio', { name: 'Removed' }))
    await waitFor(async () => expect(await headers()).toContain('Removed By'))
    await resizeByKeys(await heading(/Doc #/), ['ArrowRight', 'Enter'])
    await waitFor(() => expect(localStorage.getItem('healdan.columns.trashRemoved')).not.toBeNull())
    expect(localStorage.getItem('healdan.columns.trashMine')).toBeNull()
  })

  it('says where deleted items go when there are none, and offers nothing to restore', async () => {
    await renderApp('/trash')
    expect(await within(main()).findByText('Nothing in the Trash.')).toBeTruthy()
    expect(within(main()).getByText('What you delete comes here, and you can restore it from here.')).toBeTruthy()
    const bar = await toolbar()
    expect(within(bar).getByRole('button', { name: 'Restore' }).hasAttribute('disabled')).toBe(true)
    expect(within(bar).getByRole('button', { name: 'Remove…' }).hasAttribute('disabled')).toBe(true)
    expect(within(bar).getByRole('button', { name: 'Empty Trash…' }).hasAttribute('disabled')).toBe(true)
    await expectNoAxeViolations(main())
  })

  it("shows the selected item's details: its icon, name, kind and count, Deleted From in full, Deleted and what it holds", async () => {
    await renderApp('/trash', routes())
    await selectRows(/Old Drafts/)
    const panel = await details()
    expect(within(panel).getByRole('heading', { name: 'Old Drafts' })).toBeTruthy()
    expect(panel.querySelector('svg[data-kind="folder"]')).toBeTruthy()
    expect(within(panel).getByText('Folder · 14 items')).toBeTruthy()
    const values = Object.fromEntries(Array.from(panel.querySelectorAll('dl > div'), (pair) => [pair.querySelector('dt')!.textContent, pair.querySelector('dd')!.textContent]))
    expect(values).toEqual({
      'Deleted From': '1001.1 – Charter Dispute › Drafts',
      Deleted: formatDateTime(oldDrafts.deletedAt),
      Holds: '3 folders, 11 documents',
    })
    expect(within(panel).getByRole('button', { name: 'Restore' })).toBeTruthy()
    expect(within(panel).getByRole('button', { name: 'Remove…' })).toBeTruthy()
    await expectNoAxeViolations(panel)

    // A document's kind and size
    await selectRows(/Letter to Harbour/)
    const letter = await details()
    expect(await within(letter).findByText('Word · 209 KB')).toBeTruthy()
    expect(letter.querySelector('svg[data-kind="wordProcessing"]')).toBeTruthy()
  })

  it('restores each selected item back where it was, and says where with Open', async () => {
    const { api, router } = await renderApp('/trash', routes())
    await selectRows(/Old Drafts/, 1)
    expect(Array.from(document.querySelectorAll('[data-row-key][aria-selected="true"]'), (one) => one.getAttribute('data-row-key'))).toEqual(['t701', 't702'])
    fireEvent.click(within(await toolbar()).getByRole('button', { name: 'Restore' }))

    expect(await screen.findByText('Restored "Old Drafts" to 1001.1 › Drafts')).toBeTruthy()
    expect(screen.getByText('Restored "Letter to Harbour Master v2.docx" to 1001.1 › Correspondence')).toBeTruthy()
    expect(restoreCalls(api).map((call) => (call as { path: string }).path)).toEqual(['/v1/trash/701/restore', '/v1/trash/702/restore'])
    // The lists are asked again: the Trash's and what shows documents
    await waitFor(() => expect(api.callsTo('GET /v1/trash').length).toBeGreaterThan(1))

    const toast = screen.getByText('Restored "Old Drafts" to 1001.1 › Drafts').closest('[role="alertdialog"]') as HTMLElement
    fireEvent.click(within(toast).getByRole('button', { name: 'Open' }))
    await waitFor(() => expect(router.state.location.pathname).toBe('/folders/8'))
  })

  it('asks where for an item whose place is gone, and restores it there', async () => {
    const asked: { id: string; body: unknown }[] = []
    const { api } = await renderApp(
      '/trash',
      routes({
        'POST /v1/trash/*/restore': ({ params, body }: { params: string[]; body?: unknown }) => {
          asked.push({ id: params[0]!, body })
          if (params[0] === '702' && body === undefined) return problem(409, 'place-gone')
          return { place: params[0] === '701' ? draftsPlace : correspondencePlace, item: params[0] === '701' ? oldDrafts : harbourLetter }
        },
      }),
    )
    await selectRows(/Old Drafts/, 1)
    fireEvent.click(within(await toolbar()).getByRole('button', { name: 'Restore' }))

    const picker = await screen.findByRole('dialog', { name: 'Restore "Letter to Harbour Master v2.docx" to…' })
    expect(await screen.findByText('Restored "Old Drafts" to 1001.1 › Drafts')).toBeTruthy()
    await expectNoAxeViolations(picker)
    await userEvent.click(await within(picker).findByRole('button', { name: /1001\.1 – Charter Dispute/ }))
    await userEvent.click(await within(picker).findByRole('button', { name: /Correspondence/ }))
    const here = await within(picker).findByRole('button', { name: 'Move Here' })
    await waitFor(() => expect(here.hasAttribute('disabled')).toBe(false))
    await userEvent.click(here)

    await waitFor(() => expect(asked).toEqual([{ id: '701', body: undefined }, { id: '702', body: undefined }, { id: '702', body: { folderId: 111 } }]))
    expect(await screen.findByText('Restored "Letter to Harbour Master v2.docx" to 1001.1 › Correspondence')).toBeTruthy()
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(restoreCalls(api)).toHaveLength(3)
  })

  it("leaves a matter's top level to the server: the picker greys nothing out for the item, and says what the server refused", async () => {
    const asked: unknown[] = []
    await renderApp(
      '/trash',
      routes({
        'POST /v1/trash/*/restore': ({ body }: { body?: unknown }) => {
          asked.push(body)
          return body === undefined ? problem(409, 'place-gone') : problem(403, 'admins-only')
        },
      }),
    )
    await selectRows(/Old Drafts/)
    fireEvent.click(within(await toolbar()).getByRole('button', { name: 'Restore' }))
    const picker = await screen.findByRole('dialog', { name: 'Restore "Old Drafts" to…' })
    await userEvent.click(await within(picker).findByRole('button', { name: /1001\.1 – Charter Dispute/ }))
    const here = await within(picker).findByRole('button', { name: 'Move Here' })
    await waitFor(() => expect(here.hasAttribute('disabled')).toBe(false))
    expect(within(picker).queryByText(/Open a folder inside the matter/)).toBeNull()
    await userEvent.click(here)
    await waitFor(() => expect(asked).toEqual([undefined, { folderId: 110 }]))
    expect(await screen.findByText(/Only administrators can do that\./)).toBeTruthy()
  })

  it("says an item refused for good can't be restored from here, in the catalogue's words", async () => {
    await renderApp('/trash', routes({ 'POST /v1/trash/*/restore': problem(409, 'refused') }))
    await selectRows(/Old Drafts/)
    fireEvent.click(within(await toolbar()).getByRole('button', { name: 'Restore' }))
    expect(await screen.findByText('"Old Drafts" can\'t be restored from here.')).toBeTruthy()
    expect(screen.queryByText(/must not be shown raw/)).toBeNull()
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('sends one restore when Restore is pressed twice while it runs', async () => {
    let release = () => {}
    const { api } = await renderApp(
      '/trash',
      routes({ 'POST /v1/trash/*/restore': () => new Promise((resolve) => (release = () => resolve({ place: draftsPlace, item: oldDrafts }))) }),
    )
    await selectRows(/Old Drafts/)
    const restore = within(await toolbar()).getByRole('button', { name: 'Restore' })
    fireEvent.click(restore)
    fireEvent.click(restore)
    await waitFor(() => expect(restoreCalls(api)).toHaveLength(1))
    act(() => release())
    expect(await screen.findByText('Restored "Old Drafts" to 1001.1 › Drafts')).toBeTruthy()
    expect(restoreCalls(api)).toHaveLength(1)
  })

  it('asks before removing one item, naming it and what it holds, with the days an administrator can restore it', async () => {
    const { api } = await renderApp('/trash', routes({ 'GET /v1/trash': trashPage(mine, 45) }))
    await selectRows(/Old Drafts/)
    fireEvent.click(within(await toolbar()).getByRole('button', { name: 'Remove…' }))
    // An alert, as the one step the person can't undo themselves: its warning sign, and the line a screen reader reads
    const dialog = await screen.findByRole('alertdialog', {
      name: 'Remove "Old Drafts" from the Trash?',
      description: "You won't be able to restore it. An administrator can, for 45 days.",
    })
    expect(within(dialog).getByRole('heading').querySelector('svg[aria-hidden="true"]')).toBeTruthy()
    expect(within(dialog).getByText('It holds 3 folders and 11 documents.')).toBeTruthy()
    expect(within(dialog).getByText("You won't be able to restore it. An administrator can, for 45 days.")).toBeTruthy()
    expect(within(dialog).getByRole('button', { name: 'Remove' }).className).toContain('danger')
    await expectNoAxeViolations(dialog)

    await userEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }))
    await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull())
    expect(api.callsTo('DELETE /v1/trash/*')).toHaveLength(0)

    fireEvent.click(within(await toolbar()).getByRole('button', { name: 'Remove…' }))
    await userEvent.click(within(await screen.findByRole('alertdialog', { name: 'Remove "Old Drafts" from the Trash?' })).getByRole('button', { name: 'Remove' }))
    await waitFor(() => expect(api.callsTo('DELETE /v1/trash/*').map((call) => call.path)).toEqual(['/v1/trash/701']))
  })

  it('says a single day as a day', async () => {
    await renderApp('/trash', routes({ 'GET /v1/trash': trashPage(mine, 1) }))
    await selectRows(/Old Drafts/)
    fireEvent.click(within(await toolbar()).getByRole('button', { name: 'Remove…' }))
    const dialog = await screen.findByRole('alertdialog', { name: 'Remove "Old Drafts" from the Trash?' })
    expect(within(dialog).getByText("You won't be able to restore it. An administrator can, for 1 day.")).toBeTruthy()
  })

  it('asks before removing several, listing them', async () => {
    const { api } = await renderApp('/trash', routes())
    await selectRows(/Old Drafts/, 1)
    fireEvent.click(within(await toolbar()).getByRole('button', { name: 'Remove…' }))
    const dialog = await screen.findByRole('alertdialog', { name: 'Remove 2 items from the Trash?' })
    expect(within(dialog).getAllByRole('listitem').map((item) => item.textContent)).toEqual(['Old Drafts', 'Letter to Harbour Master v2.docx'])
    expect(within(dialog).getByText("You won't be able to restore them. An administrator can, for 30 days.")).toBeTruthy()
    await userEvent.click(within(dialog).getByRole('button', { name: 'Remove' }))
    await waitFor(() => expect(api.callsTo('DELETE /v1/trash/*').map((call) => call.path)).toEqual(['/v1/trash/701', '/v1/trash/702']))
  })

  it('asks before emptying the Trash, with how many items it holds', async () => {
    const { api } = await renderApp('/trash', routes({ 'GET /v1/trash': trashPage(mine, 45) }))
    await row(/Old Drafts/)
    fireEvent.click(within(await toolbar()).getByRole('button', { name: 'Empty Trash…' }))
    const dialog = await screen.findByRole('alertdialog', { name: 'Empty the Trash?' })
    expect(within(dialog).getByText('This removes all 3 items you deleted.')).toBeTruthy()
    expect(within(dialog).getByText("You won't be able to restore them. An administrator can, for 45 days.")).toBeTruthy()
    const confirm = within(dialog).getByRole('button', { name: 'Empty Trash' })
    expect(confirm.className).toContain('danger')
    await expectNoAxeViolations(dialog)

    await userEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }))
    await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull())
    expect(api.callsTo('DELETE /v1/trash')).toHaveLength(0)

    fireEvent.click(within(await toolbar()).getByRole('button', { name: 'Empty Trash…' }))
    await userEvent.click(within(await screen.findByRole('alertdialog', { name: 'Empty the Trash?' })).getByRole('button', { name: 'Empty Trash' }))
    await waitFor(() => expect(api.callsTo('DELETE /v1/trash')).toHaveLength(1))
  })

  it('takes the keyboard as any list: Delete is Remove…, Enter opens nothing, and the menu has Restore and Remove…', async () => {
    const { router } = await renderApp('/trash', routes())
    const target = await row(/Letter to Harbour/)
    act(() => target.focus())
    await userEvent.keyboard('{Enter}')
    expect(router.state.location.pathname).toBe('/trash')
    expect(screen.queryByRole('dialog')).toBeNull()

    await userEvent.keyboard('{Delete}')
    const dialog = await screen.findByRole('alertdialog', { name: 'Remove "Letter to Harbour Master v2.docx" from the Trash?' })
    await userEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }))
    await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull())

    fireEvent.contextMenu(await row(/Old Drafts/))
    const menu = await screen.findByRole('menu', { name: 'Actions for Old Drafts' })
    expect(within(menu).getAllByRole('menuitem').map((item) => item.textContent)).toEqual(['Restore', 'Remove…'])
    // The right-clicked row is what it acts on
    expect(Array.from(document.querySelectorAll('[data-row-key][aria-selected="true"]'), (one) => one.getAttribute('data-row-key'))).toEqual(['t701'])
    await userEvent.click(within(menu).getByRole('menuitem', { name: 'Remove…' }))
    expect(await screen.findByRole('alertdialog', { name: 'Remove "Old Drafts" from the Trash?' })).toBeTruthy()
  })

  it('leaves the focus on the row that follows what was removed, not on the button the change disabled', async () => {
    let listed = [...mine]
    const { api } = await renderApp(
      '/trash',
      routes({
        'GET /v1/trash': () => trashPage(listed),
        'DELETE /v1/trash/*': ({ params }: { params: string[] }) => {
          listed = listed.filter((one) => one.id !== Number(params[0]))
          return undefined
        },
      }),
    )
    await selectRows(/Letter to Harbour/)
    // By the toolbar's Remove…, which the dialog gives the focus back to, and which nothing selected disables
    const button = within(await toolbar()).getByRole('button', { name: 'Remove…' })
    act(() => button.focus())
    await userEvent.keyboard('{Enter}')
    await userEvent.keyboard('{Tab}{Enter}')
    await waitFor(() => expect(api.callsTo('DELETE /v1/trash/*')).toHaveLength(1))
    await waitFor(() => expect(within(main()).queryByRole('row', { name: /Letter to Harbour/ })).toBeNull())
    await waitFor(() => expect(document.activeElement?.getAttribute('data-row-key')).toBe('t703'))
  })

  it('has no switch for someone who is not an administrator, and shows them their own whatever the address asks', async () => {
    const { api } = await renderApp('/trash?view=everyone', routes())
    await row(/Old Drafts/)
    expect(within(main()).queryByRole('radiogroup')).toBeNull()
    expect(await headers()).toEqual(['Name', 'Doc #', 'Deleted From', 'Deleted'])
    expect(api.callsTo('GET /v1/trash').map((call) => call.query?.view)).toEqual(['mine'])
  })
})

describe('The Trash, for an administrator', () => {
  it("switches between Mine, Everyone's and Removed, kept in the address", async () => {
    const { api, router } = await renderApp('/trash', routes({ 'GET /v1/me': admin }))
    const show = await within(main()).findByRole('radiogroup', { name: 'Show' })
    expect(within(show).getAllByRole('radio').map((one) => one.textContent)).toEqual(['Mine', "Everyone's", 'Removed'])
    expect(within(show).getByRole('radio', { name: 'Mine' }).getAttribute('aria-checked')).toBe('true')
    await expectNoAxeViolations(main())

    await userEvent.click(within(show).getByRole('radio', { name: "Everyone's" }))
    await waitFor(() => expect(router.state.location.search).toMatchObject({ view: 'everyone' }))
    await waitFor(async () => expect(await headers()).toEqual(['Name', 'Doc #', 'Deleted From', 'Deleted By', 'Deleted']))
    expect(cells(within(await grid()).getAllByRole('row', { name: /Letter to Harbour/ }).at(-1)!)[3]).toBe('Chris Doyle')
    expect(api.callsTo('GET /v1/trash').at(-1)?.query).toMatchObject({ view: 'everyone' })
    const bar = await toolbar()
    expect(within(bar).getByRole('button', { name: 'Restore' })).toBeTruthy()
    expect(within(bar).getByRole('button', { name: 'Remove…' })).toBeTruthy()
    await expectNoAxeViolations(main())
  })

  it('shows Removed with who removed each, when, and when it goes, Restore only, and the lede', async () => {
    await renderApp('/trash?view=removed', routes({ 'GET /v1/me': admin }))
    await row(/Rent schedule 2027/)
    expect(await headers()).toEqual(['Name', 'Doc #', 'Deleted From', 'Deleted By', 'Removed By', 'Removed', 'Destroyed'])
    expect(within(main()).getByText("What people removed from their Trash. Restore puts an item back where it was deleted from; after 30 days it's destroyed.")).toBeTruthy()
    const rent = cells(await row(/Rent schedule 2027/))
    expect(rent.slice(3)).toEqual(['Chris Doyle', 'Chris Doyle', formatDate(removedRent.removedAt!), 'in 23 days'])
    const witness = await row(/Witness statement/)
    // The upgrade didn't record who removed it
    expect(within(witness).getByText('Not recorded')).toBeTruthy()
    // Under a week, in the caution colour
    expect(within(witness).getByText('in 2 days').className).toContain('caution')
    expect(within(await row(/Rent schedule 2027/)).getByText('in 23 days').className).not.toContain('caution')

    const bar = await toolbar()
    expect(within(bar).getAllByRole('button').map((button) => button.textContent)).toEqual(['Restore'])
    fireEvent.contextMenu(witness)
    const menu = await screen.findByRole('menu')
    expect(within(menu).getAllByRole('menuitem').map((item) => item.textContent)).toEqual(['Restore'])
    fireEvent.keyDown(menu, { key: 'Escape' })
    await waitFor(() => expect(screen.queryByRole('menu')).toBeNull())

    // The details say the same, with Restore only
    await selectRows(/Witness statement/)
    const panel = await details()
    const values = Object.fromEntries(Array.from(panel.querySelectorAll('dl > div'), (pair) => [pair.querySelector('dt')!.textContent, pair.querySelector('dd')!.textContent]))
    expect(values).toMatchObject({ 'Deleted By': 'Priya Nair', 'Removed By': 'Not recorded', Destroyed: 'in 2 days' })
    expect(within(panel).queryByRole('button', { name: 'Remove…' })).toBeNull()
    expect(within(panel).getByRole('button', { name: 'Restore' })).toBeTruthy()
    await expectNoAxeViolations(main())
  })

  it('says a day in the lede, not "1 days", when removed items are kept for one', async () => {
    await renderApp('/trash?view=removed', routes({ 'GET /v1/me': admin, 'GET /v1/trash': trashPage([removedRent], 1) }))
    await row(/Rent schedule 2027/)
    expect(within(main()).getByText("What people removed from their Trash. Restore puts an item back where it was deleted from; after 1 day it's destroyed.")).toBeTruthy()
  })

  it("says Everyone's is empty with the title alone, as where things go is the person's own", async () => {
    await renderApp('/trash?view=everyone', routes({ 'GET /v1/me': admin }, { everyone: [] }))
    expect(await within(main()).findByText('Nothing in the Trash.')).toBeTruthy()
    expect(within(main()).queryByText('What you delete comes here, and you can restore it from here.')).toBeNull()
    await expectNoAxeViolations(main())
  })

  it('says Removed is empty in its own words, as it holds what people removed', async () => {
    await renderApp('/trash?view=removed', routes({ 'GET /v1/me': admin }, { removed: [] }))
    expect(await within(main()).findByText('Nothing removed.')).toBeTruthy()
    expect(within(main()).queryByText('Nothing in the Trash.')).toBeNull()
    expect(within(main()).queryByText('What you delete comes here, and you can restore it from here.')).toBeNull()
    await expectNoAxeViolations(main())
  })
})
