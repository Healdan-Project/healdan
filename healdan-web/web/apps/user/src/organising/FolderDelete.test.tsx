// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { act, fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { ApiError } from '@healdan/api'
import { heldByJamie } from '../filing/testing'
import { expectNoAxeViolations } from '../test/axe'
import { children, folder, page, problem } from '../test/fakeApi'
import { renderApp } from '../test/renderApp'
import { closeMenu, drafts, lease, matterTop, routes, row, select, store, workspace } from './testing'

// Deleting folders (folders and Trash spec 4.2, 6.1, 7.4, 7.5): the ways in, the question and what it holds, and what was
// refused, per item. A click on a folder row opens it, so these focus rows rather than click them.

const menu = () => screen.findByRole('menu')
const names = async () => within(await menu()).getAllByRole('menuitem').map((one) => one.textContent)
const item = async (name: string) => within(await menu()).findByRole('menuitem', { name })
const question = (name = 'Move "Drafts" to the Trash?') => screen.findByRole('dialog', { name })
const tree = () => screen.findByRole('treegrid', { name: 'My Matters' })
const main = () => screen.getByRole('main')

async function focusRow(name: RegExp) {
  const target = await row(name)
  act(() => target.focus())
  return target
}

/** Adds a row to the selection with Cmd (Ctrl in jsdom, which isn't a Mac): a plain click on a folder would open it */
async function addRow(name: RegExp) {
  const user = userEvent.setup()
  await user.keyboard('{Control>}')
  await user.click(within(await row(name)).getByRole('rowheader'))
  await user.keyboard('{/Control}')
}

async function expandTree() {
  const box = await tree()
  fireEvent.click(within(box).getByRole('button', { name: /expand/i }))
  await within(box).findByRole('row', { name: /Correspondence/ })
  return box
}

describe('The ways in to deleting a folder', () => {
  it('Delete on a focused folder row asks, saying what it holds that the person can read, then the note', async () => {
    const { all, written } = routes()
    const { api } = await renderApp('/folders/7', all)
    await focusRow(/Drafts/)
    await userEvent.keyboard('{Delete}')
    const dialog = await question()
    expect(await within(dialog).findByText('It holds 3 folders and 11 documents.')).toBeTruthy()
    expect(within(dialog).getByText('You can restore it from the Trash, at the foot of the left pane.')).toBeTruthy()
    expect(api.callsTo('GET /v1/folders/8/contents')).toHaveLength(1)
    await expectNoAxeViolations(dialog)
    expect(written.deletedFolders).toEqual([])
  })

  it('Delete on a selected folder row asks about it, and confirming moves it to the Trash', async () => {
    const { all, written } = routes()
    await renderApp('/folders/7', all)
    await focusRow(/Drafts/)
    await userEvent.keyboard(' ')
    await userEvent.keyboard('{Delete}')
    await userEvent.click(within(await question()).getByRole('button', { name: 'Move to Trash' }))
    await waitFor(() => expect(written.deletedFolders).toEqual([8]))
    const toast = (await screen.findByText('Moved "Drafts" to the Trash')).closest('[role="alertdialog"]') as HTMLElement
    expect(within(toast).getByRole('button', { name: 'Undo' })).toBeTruthy()
    await waitFor(() => expect(within(main()).queryByRole('row', { name: /Drafts/ })).toBeNull())
  })

  it("has Move To… and Delete after Rename in a folder row's right-click menu, and Delete asks", async () => {
    await renderApp('/folders/7', routes().all)
    fireEvent.contextMenu(await row(/Drafts/))
    expect(await names()).toEqual(['Open', 'Open in New Tab', 'New Folder', 'Rename', 'Move To…', 'Delete', 'Add to Favorites'])
    await userEvent.click(await item('Delete'))
    expect(await question()).toBeTruthy()
  })

  it("has Move To… and Delete in the open folder's ⋯, and an open folder that's deleted gives way to its parent", async () => {
    const { all, written } = routes()
    const { router } = await renderApp('/folders/8', all)
    fireEvent.click(await within(main()).findByRole('button', { name: 'More for Drafts' }))
    expect(await names()).toEqual(['Rename', 'Move To…', 'Delete'])
    await userEvent.click(await item('Delete'))
    await userEvent.click(within(await question()).getByRole('button', { name: 'Move to Trash' }))
    await waitFor(() => expect(written.deletedFolders).toEqual([8]))
    await waitFor(() => expect(router.state.location.pathname).toBe('/folders/7'))
    expect(await screen.findByText('Moved "Drafts" to the Trash')).toBeTruthy()
    // The ⋯ went with the folder: the focus goes to the parent's list, not to the page
    await waitFor(() => expect(document.activeElement?.closest('[role="grid"]')).not.toBeNull())
  })

  it('in the tree, Delete on a folder asks, and on a matter says who deletes it', async () => {
    await renderApp('/folders/7', routes().all)
    const box = await expandTree()
    const matterRow = within(box).getByRole('row', { name: /1001\.1/ })
    act(() => matterRow.focus())
    await userEvent.keyboard('{Delete}')
    expect(await screen.findByText("A matter's own folder is deleted by an administrator in the Classic Interface.")).toBeTruthy()
    expect(screen.queryByRole('dialog')).toBeNull()

    const folderRow = within(box).getByRole('row', { name: /Correspondence/ })
    act(() => folderRow.focus())
    await userEvent.keyboard('{Backspace}')
    expect(await question('Move "Correspondence" to the Trash?')).toBeTruthy()
  })

  it("has Move To… and Delete in a tree folder's ⋯, and Delete asks", async () => {
    await renderApp('/folders/7', routes().all)
    const box = await expandTree()
    fireEvent.click(within(box).getByRole('button', { name: 'More for Correspondence' }))
    expect(await names()).toEqual(['Rename', 'Move To…', 'Delete'])
    await userEvent.click(await item('Delete'))
    expect(await question('Move "Correspondence" to the Trash?')).toBeTruthy()
  })

  it('greys Move To… and Delete out where the server says no, and Delete says why', async () => {
    const locked = { ...drafts, canMove: false, canDelete: false }
    const topLevel = folder({ id: 9, name: 'Pleadings', canMove: false, canDelete: false, path: [store, workspace, { id: 9, name: 'Pleadings', kind: 'folder' }] })
    await renderApp('/folders/7', routes({ 'GET /v1/folders/7/children': () => children([locked, topLevel]) }).all)
    fireEvent.contextMenu(await row(/Drafts/))
    expect((await item('Move To…')).getAttribute('aria-disabled')).toBe('true')
    expect((await item('Delete')).getAttribute('aria-disabled')).toBe('true')
    await userEvent.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByRole('menu')).toBeNull())

    // The right-click selected Drafts, which Delete takes
    await focusRow(/Drafts/)
    await userEvent.keyboard('{Delete}')
    expect(await screen.findByText("You don't have permission to delete this folder.")).toBeTruthy()
    // The fake /v1/me isn't an admin, and Pleadings is at the matter's top level
    await focusRow(/Pleadings/)
    await userEvent.keyboard(' ')
    await userEvent.keyboard('{Delete}')
    expect(await screen.findByText("Only administrators can delete a matter's top-level folders.")).toBeTruthy()
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it("shows neither Move To… nor Delete for a matter's own folder", async () => {
    await renderApp('/folders/110', { ...routes().all, 'GET /v1/folders/110/children': children([]) })
    fireEvent.click(await within(main()).findByRole('button', { name: 'More for 1001.1 – Charter Dispute' }))
    expect(await names()).toEqual(['Rename'])
  })

  it("shows neither on a matter's own folder in a list that shows it as a folder (Recent › Folders)", async () => {
    await renderApp('/recent/folders', { ...routes().all, 'GET /v1/folders/recent': page([matterTop]) })
    const grid = await within(main()).findByRole('grid')
    fireEvent.contextMenu(await within(grid).findByRole('row', { name: /Charter Dispute/ }))
    expect(await names()).not.toContain('Delete')
    expect(await names()).not.toContain('Move To…')
  })
})

describe("The toolbar's More", () => {
  const toolbar = () => screen.findByRole('toolbar', { name: 'Folder Actions' })
  const locked = { ...drafts, canDelete: false }

  it('deletes the selected folders with the documents, and keeps Delete while one of several can be deleted', async () => {
    const { all } = routes({ 'GET /v1/folders/7/children': () => children([locked, folder({ id: 9, name: 'Pleadings', path: drafts.path })]) })
    await renderApp('/folders/7', all)
    await focusRow(/Pleadings/)
    await userEvent.keyboard(' ')
    await userEvent.click(within(await toolbar()).getByRole('button', { name: 'More' }))
    expect((await item('Delete')).getAttribute('aria-disabled')).toBeNull()
    expect((await item('Rename')).getAttribute('aria-disabled')).toBe('true')
    await userEvent.click(await item('Delete'))
    expect(await question('Move "Pleadings" to the Trash?')).toBeTruthy()
    await userEvent.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())

    // Drafts can't be deleted, Pleadings can: the selection's Delete is there for what can
    await addRow(/Drafts/)
    await userEvent.click(within(await toolbar()).getByRole('button', { name: 'More' }))
    expect((await item('Delete')).getAttribute('aria-disabled')).toBeNull()
  })

  it('greys Delete out when nothing selected can be deleted, and the Delete key says why', async () => {
    const other = folder({ id: 9, name: 'Pleadings', path: drafts.path, canDelete: false })
    await renderApp('/folders/7', routes({ 'GET /v1/folders/7/children': () => children([locked, other]) }).all)
    await focusRow(/Drafts/)
    await userEvent.keyboard(' ')
    await addRow(/Pleadings/)
    await userEvent.click(within(await toolbar()).getByRole('button', { name: 'More' }))
    expect((await item('Delete')).getAttribute('aria-disabled')).toBe('true')
    await userEvent.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByRole('menu')).toBeNull())
    fireEvent.contextMenu(await row(/Pleadings/))
    expect(await screen.findByRole('menu', { name: 'Actions for 2 items' })).toBeTruthy()
    expect((await item('Delete')).getAttribute('aria-disabled')).toBe('true')
    await userEvent.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByRole('menu')).toBeNull())
    await focusRow(/Pleadings/)
    await userEvent.keyboard('{Delete}')
    expect(await screen.findByText("Drafts: You don't have permission to delete this folder.")).toBeTruthy()
    expect(screen.queryByRole('dialog')).toBeNull()
  })
})

describe('Deleting folders and documents together', () => {
  it('asks "Move 4 items to the Trash?" listing them, then deletes each with its own call, in the list\'s order', async () => {
    const { all, written } = routes()
    const { api } = await renderApp('/folders/7', all)
    await select(/Letter/, /Plan/)
    await addRow(/Drafts/)
    await userEvent.keyboard('{Delete}')
    const dialog = await question('Move 4 items to the Trash?')
    expect(within(dialog).getAllByRole('listitem').map((one) => one.textContent)).toEqual(['Drafts', 'Letter to Dunmere Pryce.docx', 'Lease.docx', 'Plan.pdf'])
    expect(within(dialog).getByText('You can restore them from the Trash, at the foot of the left pane.')).toBeTruthy()
    // What one folder of several holds isn't asked
    expect(api.callsTo('GET /v1/folders/*/contents')).toHaveLength(0)
    await userEvent.click(within(dialog).getByRole('button', { name: 'Move to Trash' }))

    await waitFor(() => expect(written.deleted).toEqual([10431, 10432, 10433]))
    expect(written.deletedFolders).toEqual([8])
    expect(api.calls.filter((call) => call.method === 'DELETE').map((call) => call.path)).toEqual(['/v1/folders/8', '/v1/documents/10431', '/v1/documents/10432', '/v1/documents/10433'])
    const toast = (await screen.findByText('Moved 4 items to the Trash')).closest('[role="alertdialog"]') as HTMLElement
    expect(within(toast).getByRole('button', { name: 'Undo' })).toBeTruthy()
  })

  it("deletes what can be deleted in a mixed selection, and says why the folder that can't wasn't, with no call for it", async () => {
    const pleadings = folder({ id: 9, name: 'Pleadings', path: drafts.path })
    const { all, written } = routes({ 'GET /v1/folders/7/children': () => children([{ ...drafts, canDelete: false }, pleadings, lease]) })
    const { api } = await renderApp('/folders/7', all)
    await select(/Lease/)
    await addRow(/Drafts/)
    await addRow(/Pleadings/)

    // Every way in has Delete for the selection: its right-click menu, More and the key
    fireEvent.contextMenu(await row(/Lease/))
    expect(await screen.findByRole('menu', { name: 'Actions for 3 items' })).toBeTruthy()
    expect((await item('Delete')).getAttribute('aria-disabled')).toBeNull()
    await closeMenu()
    await userEvent.click(within(await screen.findByRole('toolbar', { name: 'Folder Actions' })).getByRole('button', { name: 'More' }))
    expect((await item('Delete')).getAttribute('aria-disabled')).toBeNull()
    await userEvent.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByRole('menu')).toBeNull())

    await focusRow(/Lease/)
    await userEvent.keyboard('{Delete}')
    const dialog = await question('Move 3 items to the Trash?')
    expect(within(dialog).getAllByRole('listitem').map((one) => one.textContent)).toEqual(['Drafts', 'Pleadings', 'Lease.docx'])
    await userEvent.click(within(dialog).getByRole('button', { name: 'Move to Trash' }))

    const refusals = await screen.findByRole('dialog', { name: "Some Items Weren't Moved to the Trash" })
    expect(within(refusals).getAllByRole('listitem').map((one) => one.textContent)).toEqual(["Drafts: You don't have permission to delete this folder."])
    expect(api.calls.filter((call) => call.method === 'DELETE').map((call) => call.path)).toEqual(['/v1/folders/9', '/v1/documents/10432'])
    expect(written.deletedFolders).toEqual([9])
    expect(written.deleted).toEqual([10432])
    const toast = (await screen.findByText('Moved 2 items to the Trash')).closest('[role="alertdialog"]') as HTMLElement
    expect(within(toast).getByRole('button', { name: 'Undo' })).toBeTruthy()
  })

  it("says per item what was refused while the rest is done, naming nothing a folder holds that the person can't delete", async () => {
    const { all, written } = routes({ 'DELETE /v1/folders/8': problem(403, 'items-not-allowed') })
    await renderApp('/folders/7', all)
    await select(/Lease/)
    await addRow(/Drafts/)
    await userEvent.keyboard('{Delete}')
    await userEvent.click(within(await question('Move 2 items to the Trash?')).getByRole('button', { name: 'Move to Trash' }))

    const dialog = await screen.findByRole('dialog', { name: "Some Items Weren't Moved to the Trash" })
    expect(within(dialog).getAllByRole('listitem').map((one) => one.textContent)).toEqual(["Drafts: It holds items you don't have permission to delete."])
    expect(screen.queryByText(/server sentence/)).toBeNull()
    expect(written.deleted).toEqual([10432])
    expect(await screen.findByText('Moved "Lease.docx" to the Trash')).toBeTruthy()
  })

  it('names each checked-out document a folder holds that the person can read, who has it and since when', async () => {
    const holds = [{ docNumber: 10460, name: 'Defence – draft 2.docx', hold: heldByJamie }]
    const { all } = routes({ 'DELETE /v1/folders/8': new ApiError(409, 'checked-out', 'A server sentence that must not be shown raw.', { code: 'checked-out', holds }) })
    await renderApp('/folders/7', all)
    await focusRow(/Drafts/)
    await userEvent.keyboard('{Delete}')
    await userEvent.click(within(await question()).getByRole('button', { name: 'Move to Trash' }))

    const dialog = await screen.findByRole('dialog', { name: "Some Items Weren't Moved to the Trash" })
    const [line, held] = within(dialog).getAllByRole('listitem')
    expect(line!.textContent).toMatch(/^Drafts: Documents in it are checked out:/)
    expect(line!.textContent).toMatch(/They can be deleted once they're checked in or released\.$/)
    expect(held!.textContent).toMatch(/^Defence – draft 2\.docx: Checked out by Jamie Clarke since .+\.$/)
    expect(screen.queryByText(/server sentence/)).toBeNull()
    await expectNoAxeViolations(dialog)
  })
})
