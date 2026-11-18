// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { ApiError } from '@healdan/api'
import { expectNoAxeViolations } from '../test/axe'
import { children, document as doc, folder, matter, me } from '../test/fakeApi'
import { renderApp } from '../test/renderApp'
import { addRow, cacheMessage, correspondence, row, pleadings, routes, select, toolbar } from './testing'

async function openPicker() {
  await userEvent.click(within(await toolbar()).getByRole('button', { name: 'More' }))
  await userEvent.click(await screen.findByRole('menuitem', { name: 'Move To…' }))
  return screen.findByRole('dialog', { name: /^Move \d+ Documents?$/ })
}

describe('The Move To… picker', () => {
  it('starts from Recent Matters and My Matters, and never browses Stores', async () => {
    const { all } = routes()
    const { api } = await renderApp('/folders/7', all)
    await select(/Lease/, /Plan/)
    const dialog = await openPicker()
    expect(dialog.getAttribute('aria-labelledby')).toBeTruthy()
    expect(within(dialog).getByRole('heading', { name: 'Move 2 Documents' })).toBeTruthy()

    const recent = await within(dialog).findByRole('region', { name: 'Recent Matters' })
    expect(within(recent).getByRole('button', { name: /1002\.1 – Commercial Lease/ })).toBeTruthy()
    const mine = within(dialog).getByRole('region', { name: 'My Matters' })
    expect(within(mine).getByRole('button', { name: /1001\.1 – Charter Dispute/ })).toBeTruthy()
    // Each matter with its briefcase in the matters' colour
    expect(within(mine).getByRole('button', { name: /1001\.1/ }).querySelector('svg')!.getAttribute('data-kind')).toBe('matter')
    expect(within(dialog).getByRole('textbox', { name: 'Find a Matter' })).toBeTruthy()
    await expectNoAxeViolations(dialog)
    // No Store is asked for: a Store's id is 2
    expect(api.callsTo('GET /v1/folders/2')).toHaveLength(0)
  })

  it("names each matter and its client apart, in the exact form the left pane's rows are named", async () => {
    const { all } = routes()
    await renderApp('/folders/7', all)
    await select(/Lease/)
    const dialog = await openPicker()
    const recent = await within(dialog).findByRole('region', { name: 'Recent Matters' })
    // The matter, a space, then its client: one name, which a screen reader reads as it is drawn
    expect(within(recent).getByRole('button', { name: '1002.1 – Commercial Lease Brightwell' })).toBeTruthy()
    expect(within(recent).getByRole('button', { name: '1002.1 – Commercial Lease Brightwell' }).querySelector('[data-client]')?.textContent).toBe('Brightwell')
  })

  it('leaves the focus on the list once the documents are moved, on the row that follows them, not on the page', async () => {
    const { all } = routes()
    await renderApp('/folders/7', all)
    await select(/Lease/)
    const dialog = await openPicker()
    await userEvent.click(await within(dialog).findByRole('button', { name: /1001\.1 – Charter Dispute/ }))
    await userEvent.click(await within(dialog).findByRole('button', { name: /Correspondence/ }))
    const here = await within(dialog).findByRole('button', { name: 'Move Here' })
    await waitFor(() => expect(here.hasAttribute('disabled')).toBe(false))
    await userEvent.click(here)
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    await waitFor(() => expect(globalThis.document.activeElement?.closest('[role="grid"]')).toBeTruthy())
  })

  it("leaves a filed email's message alone when it shows a moved document as an attachment's copy, as a moved copy is still its copy", async () => {
    const { all, written } = routes()
    const { queryClient } = await renderApp('/folders/7', all)
    const showsPlan = cacheMessage(queryClient, 10500, 10433)
    const showsOther = cacheMessage(queryClient, 10501, 10999)
    await select(/Plan/)
    const dialog = await openPicker()
    await userEvent.click(await within(dialog).findByRole('button', { name: /1001\.1 – Charter Dispute/ }))
    await userEvent.click(await within(dialog).findByRole('button', { name: /Correspondence/ }))
    const here = await within(dialog).findByRole('button', { name: 'Move Here' })
    await waitFor(() => expect(here.hasAttribute('disabled')).toBe(false))
    await userEvent.click(here)

    await waitFor(() => expect(written.moved).toEqual([{ docNumber: 10433, folderId: 111 }]))
    // The toast comes once the lists have been asked again
    expect(await screen.findByText('Moved 1 document to Correspondence')).toBeTruthy()
    expect(queryClient.getQueryState(showsPlan)?.isInvalidated).toBe(false)
    expect(queryClient.getQueryState(showsOther)?.isInvalidated).toBe(false)
  })

  it('moves the selection into a folder of the chosen matter, each document with its own call', async () => {
    const { all, written } = routes()
    await renderApp('/folders/7', all)
    await select(/Lease/, /Plan/)
    const dialog = await openPicker()
    await userEvent.click(await within(dialog).findByRole('button', { name: /1001\.1 – Charter Dispute/ }))

    // A matter's top level takes no documents; a search folder isn't offered
    expect((await within(dialog).findByRole('button', { name: /Correspondence/ })).querySelector('svg')!.getAttribute('data-kind')).toBe('folder')
    expect(within(dialog).queryByRole('button', { name: /My Recent Docs Here/ })).toBeNull()
    expect(within(dialog).getByRole('button', { name: 'Move Here' }).hasAttribute('disabled')).toBe(true)
    expect(within(dialog).getByText('Open a folder inside the matter to move the documents into it.')).toBeTruthy()
    await expectNoAxeViolations(dialog)

    await userEvent.click(within(dialog).getByRole('button', { name: /Correspondence/ }))
    const here = await within(dialog).findByRole('button', { name: 'Move Here' })
    await waitFor(() => expect(here.hasAttribute('disabled')).toBe(false))
    await userEvent.click(here)

    await waitFor(() =>
      expect(written.moved).toEqual([
        { docNumber: 10432, folderId: 111 },
        { docNumber: 10433, folderId: 111 },
      ]),
    )
    expect(await screen.findByText('Moved 2 documents to Correspondence')).toBeTruthy()
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    const grid = await within(screen.getByRole('main')).findByRole('grid')
    await waitFor(() => expect(within(grid).queryByRole('row', { name: /Lease/ })).toBeNull())
  })

  it('goes back up a level, and back to the matters', async () => {
    const { all } = routes()
    await renderApp('/folders/7', all)
    await select(/Lease/)
    const dialog = await openPicker()
    await userEvent.click(await within(dialog).findByRole('button', { name: /1001\.1 – Charter Dispute/ }))
    await userEvent.click(await within(dialog).findByRole('button', { name: /Correspondence/ }))
    await userEvent.click(await within(dialog).findByRole('button', { name: 'Up One Level' }))
    expect(await within(dialog).findByRole('button', { name: /Correspondence/ })).toBeTruthy()
    await userEvent.click(within(dialog).getByRole('button', { name: 'Back to Matters' }))
    expect(await within(dialog).findByRole('region', { name: 'Recent Matters' })).toBeTruthy()
  })

  it('searches matters with the search box\'s suggestions', async () => {
    const { all } = routes({
      'GET /v1/suggest': { matters: [{ id: 14, number: 3, name: 'Harbour Lease', client: { number: 1003, name: 'Quay Holdings' } }], documents: [] },
      'GET /v1/matters/14': matter({ id: 14, number: 3, display: '1003.3 – Harbour Lease', folderId: 140 }),
      'GET /v1/folders/140': folder({ id: 140, name: '1003.3 – Harbour Lease', kind: 'matter', canFile: false }),
      'GET /v1/folders/140/children': { items: [folder({ id: 141, name: 'Contracts' })], offset: 0, limit: 200, total: 1 },
    })
    await renderApp('/folders/7', all)
    await select(/Lease/)
    const dialog = await openPicker()
    await userEvent.type(within(dialog).getByRole('textbox', { name: 'Find a Matter' }), 'Harbour')
    const found = await within(dialog).findByRole('button', { name: /1003\.3 – Harbour Lease/ })
    expect(found.querySelector('svg')!.getAttribute('data-kind')).toBe('matter')
    await userEvent.click(found)
    expect(await within(dialog).findByRole('button', { name: /Contracts/ })).toBeTruthy()
  })

  it('says so when no matter is found', async () => {
    const { all } = routes({ 'GET /v1/suggest': { matters: [], documents: [] } })
    await renderApp('/folders/7', all)
    await select(/Lease/)
    const dialog = await openPicker()
    await userEvent.type(within(dialog).getByRole('textbox', { name: 'Find a Matter' }), 'Zzz')
    expect(await within(dialog).findByText('Nothing found.')).toBeTruthy()
  })

  it('names each refused document and why, and lets the others move', async () => {
    const copy = doc({ docNumber: 10600, name: 'Lease.docx' })
    const { all, written } = routes({
      'POST /v1/documents/10432/move': new ApiError(409, 'already-filed', 'A server sentence that must not be shown raw.', { code: 'already-filed', document: copy }),
    })
    await renderApp('/folders/7', all)
    await select(/Lease/, /Plan/)
    const picker = await openPicker()
    await userEvent.click(await within(picker).findByRole('button', { name: /1001\.1 – Charter Dispute/ }))
    await userEvent.click(await within(picker).findByRole('button', { name: /Correspondence/ }))
    const here = await within(picker).findByRole('button', { name: 'Move Here' })
    await waitFor(() => expect(here.hasAttribute('disabled')).toBe(false))
    await userEvent.click(here)

    const dialog = await screen.findByRole('dialog', { name: "Some Documents Weren't Moved" })
    expect(within(dialog).getByRole('listitem').textContent).toBe('Lease.docx: Already in this matter as # 10600')
    expect(written.moved).toEqual([{ docNumber: 10433, folderId: 111 }])
    expect(await screen.findByText('Moved 1 document to Correspondence')).toBeTruthy()
  })
})

// Moving a folder with the picker (folders and Trash spec 7.5): the places it can't go are greyed out, with the reason
describe('The Move To… picker, for a folder', () => {
  const admin = { 'GET /v1/me': { ...me, admin: true } }
  /** Matter 1001.1 holds Correspondence (7), where Drafts is, Pleadings and a search folder */
  const pickerRoutes = (over = {}) =>
    routes({
      'GET /v1/folders/110/children': children([correspondence, pleadings, folder({ id: 114, name: 'My Recent Docs Here', kind: 'searchFolder' })]),
      'GET /v1/folders/113': pleadings,
      'GET /v1/folders/113/children': children([]),
      ...over,
    })

  async function pickFor(name: RegExp, title: string) {
    fireEvent.contextMenu(await row(name))
    await userEvent.click(await screen.findByRole('menuitem', { name: 'Move To…' }))
    return screen.findByRole('dialog', { name: title })
  }
  const here = (dialog: HTMLElement) => within(dialog).findByRole('button', { name: 'Move Here' })
  /** What says why Move Here is greyed out */
  const why = (button: HTMLElement) => globalThis.document.getElementById(button.getAttribute('aria-describedby') ?? '')?.textContent

  it("greys out the folder itself, which doesn't open, and Move Here where it already is", async () => {
    const { all } = pickerRoutes()
    const { api } = await renderApp('/folders/7', all)
    const dialog = await pickFor(/Drafts/, 'Move "Drafts"')
    await userEvent.click(await within(dialog).findByRole('button', { name: /1001\.1 – Charter Dispute/ }))
    await userEvent.click(await within(dialog).findByRole('button', { name: /Correspondence/ }))

    const itself = await within(dialog).findByRole('button', { name: 'Drafts The folder itself' })
    expect(itself.hasAttribute('disabled')).toBe(true)
    fireEvent.click(itself)
    expect(api.callsTo('GET /v1/folders/8/children')).toHaveLength(0)
    const button = await here(dialog)
    await waitFor(() => expect(why(button)).toBe('Already there'))
    expect(button.hasAttribute('disabled')).toBe(true)
    await expectNoAxeViolations(dialog)
  })

  it("greys Move Here out at a matter's top level for anyone but an administrator, and lists no search folder", async () => {
    const { all } = pickerRoutes()
    await renderApp('/folders/7', all)
    const dialog = await pickFor(/Drafts/, 'Move "Drafts"')
    await userEvent.click(await within(dialog).findByRole('button', { name: /1001\.1 – Charter Dispute/ }))
    expect(await within(dialog).findByRole('button', { name: /Pleadings/ })).toBeTruthy()
    expect(within(dialog).queryByRole('button', { name: /My Recent Docs Here/ })).toBeNull()
    const button = await here(dialog)
    await waitFor(() => expect(why(button)).toBe('Administrators only'))
    expect(button.hasAttribute('disabled')).toBe(true)
  })

  it("lets an administrator move a folder to a matter's top level", async () => {
    const { all, written } = pickerRoutes(admin)
    await renderApp('/folders/7', all)
    const dialog = await pickFor(/Drafts/, 'Move "Drafts"')
    await userEvent.click(await within(dialog).findByRole('button', { name: /1001\.1 – Charter Dispute/ }))
    const button = await here(dialog)
    await waitFor(() => expect(button.hasAttribute('disabled')).toBe(false))
    expect(button.getAttribute('aria-describedby')).toBeNull()
    await userEvent.click(button)
    await waitFor(() => expect(written.movedFolders).toEqual([{ id: 8, folderId: 110 }]))
    expect(await screen.findByText('Moved "Drafts" to 1001.1 – Charter Dispute')).toBeTruthy()
  })

  it("with documents in the selection, greys a matter's top level out for them, as it does now", async () => {
    const { all } = pickerRoutes(admin)
    await renderApp('/folders/7', all)
    await select(/Lease/)
    await addRow(/Drafts/)
    const dialog = await pickFor(/Lease/, 'Move 2 Items')
    await userEvent.click(await within(dialog).findByRole('button', { name: /1001\.1 – Charter Dispute/ }))
    expect(await within(dialog).findByText('Open a folder inside the matter to move the documents into it.')).toBeTruthy()
    expect((await here(dialog)).hasAttribute('disabled')).toBe(true)
  })

  it("moves each item with its own call, the documents first, and says where they went", async () => {
    const { all, written } = pickerRoutes()
    const { api } = await renderApp('/folders/7', all)
    await select(/Lease/, /Plan/)
    await addRow(/Drafts/)
    const dialog = await pickFor(/Lease/, 'Move 3 Items')
    await userEvent.click(await within(dialog).findByRole('button', { name: /1001\.1 – Charter Dispute/ }))
    await userEvent.click(await within(dialog).findByRole('button', { name: /Pleadings/ }))
    const button = await here(dialog)
    await waitFor(() => expect(button.hasAttribute('disabled')).toBe(false))
    await userEvent.click(button)

    await waitFor(() => expect(written.movedFolders).toEqual([{ id: 8, folderId: 113 }]))
    expect(written.moved).toEqual([
      { docNumber: 10432, folderId: 113 },
      { docNumber: 10433, folderId: 113 },
    ])
    expect(api.calls.filter((call) => call.method === 'POST').map((call) => call.path)).toEqual(['/v1/documents/10432/move', '/v1/documents/10433/move', '/v1/folders/8/move'])
    expect(await screen.findByText('Moved 3 items to Pleadings')).toBeTruthy()
  })
})
