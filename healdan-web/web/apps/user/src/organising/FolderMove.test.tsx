// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { act, fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { ApiError } from '@healdan/api'
import { folderMoveRefusal } from '../actions/folders'
import { ROWS_TYPE } from '../filing/dropTargets'
import { heldByJamie } from '../filing/testing'
import { expectNoAxeViolations } from '../test/axe'
import { children, document as doc, folder, matter, page, problem } from '../test/fakeApi'
import { renderApp } from '../test/renderApp'
import { pageToken, readRows } from './rows'
import { addRow, closeMenu, contracts, correspondence, drafts, lease, licence, licenceTop, plan, pleadings, routes, row, select, store, transfer, workspace } from './testing'

// Moving folders (folders and Trash spec 4.3, 6.2, 7.5): by drag onto the tree and by Move To…, asking first when one
// leaves its matter, with what's refused said per item while the rest is done. A click on a folder row opens it, so these
// focus rows, or add them to the selection with Ctrl, rather than click them.

const step = (one: { id: number; name: string }) => ({ id: one.id, name: one.name, kind: 'folder' as const })
const minutes = folder({ id: 9, name: 'Minutes', path: [...correspondence.path, step({ id: 9, name: 'Minutes' })] })
/** A folder inside Drafts, which Drafts can't be moved into */
const oldVersions = folder({ id: 81, name: 'Old Versions', path: [...drafts.path, step({ id: 81, name: 'Old Versions' })] })
/** What Drafts is as a drag carries it */
const drafted = { id: 8, name: 'Drafts', parentId: 7, matterId: 11, canMove: true, canDelete: true, kind: 'folder', topLevel: false }

/**
 * Folder 7's routes, with matter 1001.1 holding Correspondence (7) and Pleadings in the tree, and matter 1003.2 in My Matters
 * as well, holding Contracts
 */
function moveRoutes(over = {}) {
  return routes({
    'GET /v1/matters/mine': page([matter(), licence]),
    'GET /v1/folders/110/children': children([correspondence, pleadings]),
    'GET /v1/folders/113': pleadings,
    'GET /v1/folders/113/children': children([]),
    'GET /v1/folders/150': licenceTop,
    'GET /v1/folders/150/children': children([contracts]),
    'GET /v1/folders/151': contracts,
    'GET /v1/folders/151/children': children([]),
    'GET /v1/folders/8/children': children([oldVersions]),
    ...over,
  })
}

const tree = () => screen.findByRole('treegrid', { name: 'My Matters' })
const treeRow = async (name: RegExp) => within(await tree()).findByRole('row', { name })
/** The text of a folder in the tree, where a drop lands */
const treeLabel = async (name: RegExp) => within(await treeRow(name)).getByText(name)

async function expand(name: RegExp) {
  fireEvent.click(within(await treeRow(name)).getByRole('button', { name: /expand/i }))
}

/** Drags the row (or the tree's row) and drops what it carries on a folder of the tree */
async function dragOnto(source: HTMLElement, target: RegExp) {
  const data = transfer()
  fireEvent.dragStart(source, { dataTransfer: data })
  fireEvent.drop(await treeLabel(target), { dataTransfer: data })
  return data
}

const question = (name: string) => screen.findByRole('dialog', { name })
const posts = (api: { calls: { method: string; path: string }[] }) => api.calls.filter((call) => call.method === 'POST').map((call) => call.path)

async function focusRow(name: RegExp) {
  const target = await row(name)
  act(() => target.focus())
  return target
}

describe('Dragging a folder', () => {
  it("carries folder rows, and a folder in the tree, under the app's own type with this page's token, and a matter not at all", async () => {
    await renderApp('/folders/7', moveRoutes().all)
    const data = transfer()
    fireEvent.dragStart(await row(/Drafts/), { dataTransfer: data })
    expect(JSON.parse(data.getData(ROWS_TYPE)).token).toBe(pageToken)
    expect(readRows(data.getData(ROWS_TYPE))).toEqual({ documents: [], folders: [drafted] })

    await expand(/^Correspondence/)
    const inTree = await treeRow(/^Drafts/)
    expect(inTree.draggable).toBe(true)
    const fromTree = transfer()
    expect(fireEvent.dragStart(inTree, { dataTransfer: fromTree })).toBe(true)
    expect(fromTree.effectAllowed).toBe('move')
    expect(readRows(fromTree.getData(ROWS_TYPE))).toEqual({ documents: [], folders: [drafted] })

    const matterRow = await treeRow(/1001\.1/)
    expect(matterRow.draggable).toBe(false)
    expect(fireEvent.dragStart(matterRow, { dataTransfer: transfer() })).toBe(false)
  })

  it('moves a folder dropped on a folder of the tree, which lights up, at once within its matter', async () => {
    const { all, written } = moveRoutes()
    await renderApp('/folders/7', all)
    const target = await treeLabel(/Pleadings/)
    fireEvent.dragEnter(target, { dataTransfer: { types: [ROWS_TYPE] } })
    expect(fireEvent.dragOver(target, { dataTransfer: { types: [ROWS_TYPE] } })).toBe(false)
    expect((await tree()).querySelector('[data-drop-target="true"]')?.textContent).toMatch(/Pleadings/)

    await dragOnto(await row(/Drafts/), /Pleadings/)
    await waitFor(() => expect(written.movedFolders).toEqual([{ id: 8, folderId: 113 }]))
    expect(await screen.findByText('Moved "Drafts" to Pleadings')).toBeTruthy()
    expect(screen.queryByRole('dialog')).toBeNull()
    await waitFor(() => expect(within(screen.getByRole('main')).queryByRole('row', { name: /Drafts/ })).toBeNull())
  })

  it('moves a folder of the tree dropped on another', async () => {
    const { all, written } = moveRoutes()
    await renderApp('/folders/7', all)
    await expand(/^Correspondence/)
    await dragOnto(await treeRow(/^Drafts/), /Pleadings/)
    await waitFor(() => expect(written.movedFolders).toEqual([{ id: 8, folderId: 113 }]))
  })

  it('names the folder as it arrived, when the server gave it another name on its way in', async () => {
    const { all } = moveRoutes({ 'POST /v1/folders/8/move': { ...drafts, name: 'Drafts(1)' } })
    await renderApp('/folders/7', all)
    await dragOnto(await row(/Drafts/), /Pleadings/)
    expect(await screen.findByText('Moved "Drafts(1)" to Pleadings')).toBeTruthy()
  })

  it('does nothing but say so when a folder is dropped on itself or into its own tree', async () => {
    const { all, written } = moveRoutes()
    const { api } = await renderApp('/folders/7', all)
    await expand(/^Correspondence/)
    await dragOnto(await row(/Drafts/), /^Drafts/)
    // A sentence, not the picker's label
    const intoItself = "A folder can't be moved into itself or a folder inside it."
    expect(await screen.findByText(intoItself)).toBeTruthy()
    await expand(/^Drafts/)
    await dragOnto(await row(/Drafts/), /Old Versions/)
    await waitFor(() => expect(screen.getAllByText(intoItself)).toHaveLength(2))
    expect(written.movedFolders).toEqual([])
    expect(api.callsTo('POST /v1/folders/*/move')).toHaveLength(0)
  })

  it("says why a folder that can't be moved isn't, with no call", async () => {
    const locked = { ...drafts, canMove: false }
    const topLevel = folder({ id: 12, name: 'Exhibits', canMove: false, path: [store, workspace, step({ id: 12, name: 'Exhibits' })] })
    const { all } = moveRoutes({ 'GET /v1/folders/7/children': () => children([locked, topLevel, lease]) })
    const { api } = await renderApp('/folders/7', all)
    await dragOnto(await row(/Drafts/), /Pleadings/)
    expect(await screen.findByText("You don't have permission to move this folder.")).toBeTruthy()
    // The fake /v1/me isn't an admin
    await dragOnto(await row(/Exhibits/), /Pleadings/)
    expect(await screen.findByText("Only administrators can move a matter's top-level folders.")).toBeTruthy()
    expect(api.callsTo('POST /v1/folders/*/move')).toHaveLength(0)
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it("says only administrators move a folder into a matter's top level when one is dropped on the matter, with no call", async () => {
    const { all, written } = moveRoutes()
    const { api } = await renderApp('/folders/7', all)
    // The fake /v1/me isn't an admin, and Drafts isn't a top-level folder: what's refused is where it was going
    await dragOnto(await row(/Drafts/), /1001\.1/)
    expect(await screen.findByText("Only administrators can move folders into a matter's top level.")).toBeTruthy()
    expect(screen.queryByText("Only administrators can move a matter's top-level folders.")).toBeNull()
    expect(written.movedFolders).toEqual([])
    expect(api.callsTo('POST /v1/folders/*/move')).toHaveLength(0)
  })

  it('moves nothing from a drag that began in another page', async () => {
    const { all, written } = moveRoutes()
    const { api } = await renderApp('/folders/7', all)
    const foreign = transfer()
    foreign.setData(ROWS_TYPE, JSON.stringify({ token: 'another page', documents: [], folders: [drafted] }))
    fireEvent.drop(await treeLabel(/Pleadings/), { dataTransfer: foreign })
    await new Promise((resolve) => setTimeout(resolve, 50))
    expect(written.movedFolders).toEqual([])
    expect(api.callsTo('POST /v1/folders/*/move')).toHaveLength(0)
  })
})

describe('A folder leaving its matter', () => {
  const asked = 'Move "Drafts" into 1003.2 – Licence Review?'
  const line = "Its 11 documents will take 1003.2's client and matter, and the properties its template sets."

  it('asks first when dropped in another matter: Cancel moves nothing, and Move moves it', async () => {
    const { all, written } = moveRoutes()
    const { api } = await renderApp('/folders/7', all)
    await expand(/1003\.2/)
    await dragOnto(await row(/Drafts/), /Contracts/)
    const dialog = await question(asked)
    expect(await within(dialog).findByText(line)).toBeTruthy()
    // What the folder holds that the person can read, asked afresh
    expect(api.callsTo('GET /v1/folders/8/contents')).toHaveLength(1)
    await expectNoAxeViolations(dialog)
    await userEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(api.callsTo('POST /v1/folders/*/move')).toHaveLength(0)

    await dragOnto(await row(/Drafts/), /Contracts/)
    await userEvent.click(within(await question(asked)).getByRole('button', { name: 'Move' }))
    await waitFor(() => expect(written.movedFolders).toEqual([{ id: 8, folderId: 151 }]))
    expect(await screen.findByText('Moved "Drafts" to Contracts')).toBeTruthy()
  })

  it('asks first when Move To… takes it to another matter', async () => {
    const { all, written } = moveRoutes()
    const { api } = await renderApp('/folders/7', all)
    fireEvent.contextMenu(await row(/Drafts/))
    await userEvent.click(await screen.findByRole('menuitem', { name: 'Move To…' }))
    const picker = await question('Move "Drafts"')
    await userEvent.click(await within(picker).findByRole('button', { name: /1003\.2 – Licence Review/ }))
    await userEvent.click(await within(picker).findByRole('button', { name: /Contracts/ }))
    const here = await within(picker).findByRole('button', { name: 'Move Here' })
    await waitFor(() => expect(here.hasAttribute('disabled')).toBe(false))
    await userEvent.click(here)

    const dialog = await question(asked)
    expect(await within(dialog).findByText(line)).toBeTruthy()
    expect(api.callsTo('POST /v1/folders/*/move')).toHaveLength(0)
    await userEvent.click(within(dialog).getByRole('button', { name: 'Move' }))
    await waitFor(() => expect(written.movedFolders).toEqual([{ id: 8, folderId: 151 }]))
  })

  it('asks once for each folder leaving, in order, and Cancel skips only that one, while the documents go too', async () => {
    const { all, written } = moveRoutes({ 'GET /v1/folders/7/children': () => children([drafts, minutes, lease, plan]) })
    const { api } = await renderApp('/folders/7', all)
    await select(/Lease/)
    await addRow(/Drafts/)
    await addRow(/Minutes/)
    await expand(/1003\.2/)
    await dragOnto(await row(/Lease/), /Contracts/)

    await userEvent.click(within(await question(asked)).getByRole('button', { name: 'Cancel' }))
    const next = await question('Move "Minutes" into 1003.2 – Licence Review?')
    expect(api.callsTo('POST /v1/*/*/move')).toHaveLength(0)
    await userEvent.click(within(next).getByRole('button', { name: 'Move' }))

    await waitFor(() => expect(written.movedFolders).toEqual([{ id: 9, folderId: 151 }]))
    expect(written.moved).toEqual([{ docNumber: 10432, folderId: 151 }])
    // The documents first, then the folders, so a document goes where the person saw it go
    expect(posts(api)).toEqual(['/v1/documents/10432/move', '/v1/folders/9/move'])
    expect(api.callsTo('GET /v1/folders/8/contents')).toHaveLength(1)
    expect(api.callsTo('GET /v1/folders/9/contents')).toHaveLength(1)
    expect(await screen.findByText('Moved 2 items to Contracts')).toBeTruthy()
  })

  it('moves documents dragged on their own into another matter at once, as now', async () => {
    const { all, written } = moveRoutes()
    await renderApp('/folders/7', all)
    await expand(/1003\.2/)
    await select(/Lease/)
    await dragOnto(await row(/Lease/), /Contracts/)
    await waitFor(() => expect(written.moved).toEqual([{ docNumber: 10432, folderId: 151 }]))
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(await screen.findByText('Moved 1 document to Contracts')).toBeTruthy()
  })
})

/** A folder move's refusal by the server, with the check that made it */
function refusedFor(code: 'forbidden' | 'admins-only', cause: string): ApiError {
  return new ApiError(403, code, 'A server sentence that must not be shown raw.', { status: 403, code, cause })
}

describe('What a folder move refused', () => {
  it('is said per item, in the catalogue\'s words, naming nothing hidden, while the rest is moved', async () => {
    const named = (id: number, name: string) => folder({ id, name, path: [...correspondence.path, step({ id, name })] })
    const holds = [{ docNumber: 10460, name: 'Defence – draft 2.docx', hold: heldByJamie }]
    const copy = doc({ docNumber: 10600, name: 'Re: Charter.eml' })
    // A top-level folder the server refuses as one, which only a fake's canMove lets reach it
    const exhibits = folder({ id: 16, name: 'Exhibits', path: [store, workspace, step({ id: 16, name: 'Exhibits' })] })
    const { all, written } = moveRoutes({
      'GET /v1/folders/7/children': () =>
        children([drafts, minutes, named(10, 'Archive'), named(12, 'Notes'), named(14, 'Emails'), exhibits, named(18, 'Ledger'), lease, plan]),
      'POST /v1/folders/8/move': problem(403, 'items-not-allowed'),
      'POST /v1/folders/9/move': new ApiError(409, 'checked-out', 'A server sentence that must not be shown raw.', { code: 'checked-out', holds }),
      'POST /v1/folders/10/move': problem(409, 'name-taken'),
      // Not top-level itself: the top level it was going into is what's refused
      'POST /v1/folders/12/move': refusedFor('admins-only', 'into-top-level'),
      'POST /v1/folders/14/move': new ApiError(409, 'already-filed', 'A server sentence that must not be shown raw.', { code: 'already-filed', document: copy }),
      'POST /v1/folders/16/move': refusedFor('admins-only', 'top-level'),
      // Its canMove says it may move, and the server says what's missing is Add where it was going
      'POST /v1/folders/18/move': refusedFor('forbidden', 'no-add'),
    })
    await renderApp('/folders/7', all)
    await select(/Lease/, /Plan/)
    for (const name of [/Drafts/, /Minutes/, /Archive/, /Notes/, /Emails/, /Exhibits/, /Ledger/]) await addRow(name)
    await dragOnto(await row(/Lease/), /Pleadings/)

    const dialog = await screen.findByRole('dialog', { name: "Some Items Weren't Moved" })
    const lines = within(dialog).getAllByRole('listitem').map((one) => one.textContent)
    expect(lines[0]).toBe("Drafts: It holds items you don't have permission to move.")
    // The check-outs it names, then what can be done about them, as a delete's refusal says
    expect(lines[1]).toMatch(/^Minutes: Documents in it are checked out:Defence – draft 2\.docx: Checked out by Jamie Clarke since .+\.It can be moved once they're checked in or released\.$/)
    expect(lines[2]).toMatch(/^Defence – draft 2\.docx: Checked out by Jamie Clarke since .+\.$/)
    expect(lines.slice(3)).toEqual([
      "Archive: There's already one with that name here.",
      "Notes: Only administrators can move folders into a matter's top level.",
      'Emails: Already in this matter as # 10600',
      "Exhibits: Only administrators can move a matter's top-level folders.",
      "Ledger: You don't have permission to add folders there.",
    ])
    expect(screen.queryByText(/server sentence/)).toBeNull()
    await expectNoAxeViolations(dialog)
    expect(written.moved).toEqual([
      { docNumber: 10432, folderId: 113 },
      { docNumber: 10433, folderId: 113 },
    ])
    expect(await screen.findByText('Moved 2 documents to Pleadings')).toBeTruthy()
  })

  it("says which check the server's forbidden and admins-only were by their cause, whatever the list said of the folder", () => {
    expect(folderMoveRefusal(refusedFor('forbidden', 'no-move'), undefined).reason).toBe("You don't have permission to move this folder.")
    expect(folderMoveRefusal(refusedFor('forbidden', 'no-add'), undefined).reason).toBe("You don't have permission to add folders there.")
    expect(folderMoveRefusal(refusedFor('admins-only', 'top-level'), undefined).reason).toBe("Only administrators can move a matter's top-level folders.")
    expect(folderMoveRefusal(refusedFor('admins-only', 'into-top-level'), undefined).reason).toBe("Only administrators can move folders into a matter's top level.")
    // Without a cause it knows, the catalogue's words for the code
    expect(folderMoveRefusal(problem(403, 'forbidden'), undefined).reason).toBe("You don't have permission to do that.")
  })
})

describe('Move To… for folders', () => {
  const toolbar = () => screen.findByRole('toolbar', { name: 'Folder Actions' })
  const item = async (name: string) => within(await screen.findByRole('menu')).findByRole('menuitem', { name })

  it("moves what can be moved in a mixed selection, and says why the folder that can't wasn't, with no call for it", async () => {
    const { all, written } = moveRoutes({ 'GET /v1/folders/7/children': () => children([{ ...drafts, canMove: false }, lease]) })
    const { api } = await renderApp('/folders/7', all)
    await select(/Lease/)
    await addRow(/Drafts/)

    // Every way in has Move To… for the selection: its right-click menu and the toolbar's More
    fireEvent.contextMenu(await row(/Lease/))
    expect((await item('Move To…')).getAttribute('aria-disabled')).toBeNull()
    await closeMenu()
    await userEvent.click(within(await toolbar()).getByRole('button', { name: 'More' }))
    await userEvent.click(await item('Move To…'))

    const picker = await question('Move 2 Items')
    await userEvent.click(await within(picker).findByRole('button', { name: /1001\.1 – Charter Dispute/ }))
    await userEvent.click(await within(picker).findByRole('button', { name: /Pleadings/ }))
    const here = await within(picker).findByRole('button', { name: 'Move Here' })
    await waitFor(() => expect(here.hasAttribute('disabled')).toBe(false))
    await userEvent.click(here)

    const refusals = await screen.findByRole('dialog', { name: "Some Items Weren't Moved" })
    expect(within(refusals).getAllByRole('listitem').map((one) => one.textContent)).toEqual(["Drafts: You don't have permission to move this folder."])
    expect(written.moved).toEqual([{ docNumber: 10432, folderId: 113 }])
    expect(api.callsTo('POST /v1/folders/*/move')).toHaveLength(0)
    expect(await screen.findByText('Moved 1 document to Pleadings')).toBeTruthy()
  })

  it('greys Move To… out when nothing selected can be moved', async () => {
    const other = { ...minutes, canMove: false }
    await renderApp('/folders/7', moveRoutes({ 'GET /v1/folders/7/children': () => children([{ ...drafts, canMove: false }, other]) }).all)
    await focusRow(/Drafts/)
    await userEvent.keyboard(' ')
    await addRow(/Minutes/)
    await userEvent.click(within(await toolbar()).getByRole('button', { name: 'More' }))
    expect((await item('Move To…')).getAttribute('aria-disabled')).toBe('true')
    await closeMenu()
    fireEvent.contextMenu(await row(/Minutes/))
    expect(await screen.findByRole('menu', { name: 'Actions for 2 items' })).toBeTruthy()
    expect((await item('Move To…')).getAttribute('aria-disabled')).toBe('true')
  })

  it("has the toolbar's Move To… and Delete act on a selection holding folders, as the menus do", async () => {
    await renderApp('/folders/7', moveRoutes().all)
    await select(/Lease/)
    await addRow(/Drafts/)
    await userEvent.click(within(await toolbar()).getByRole('button', { name: 'More' }))
    await userEvent.click(await item('Move To…'))
    const picker = await question('Move 2 Items')
    await userEvent.click(within(picker).getByRole('button', { name: 'Cancel' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())

    await userEvent.click(within(await toolbar()).getByRole('button', { name: 'More' }))
    await userEvent.click(await item('Delete'))
    const asking = await question('Move 2 items to the Trash?')
    expect(within(asking).getAllByRole('listitem').map((one) => one.textContent)).toEqual(['Drafts', 'Lease.docx'])
  })
})
