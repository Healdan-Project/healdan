// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { launch } from '../actions/launch'
import { letter } from '../filing/testing'
import { drafts, lease, plan, routes, row, select, toolbar } from '../organising/testing'
import { expectNoAxeViolations } from '../test/axe'
import { children, document as doc, folder, matter, page } from '../test/fakeApi'
import { renderApp } from '../test/renderApp'
import { setViewport } from '../test/viewport'

// The right-click menus (changed after M6: the owner's dogfooding punch list, 2026-11-14): a right-click, Shift+F10 or the
// menu key opens one in place of the browser's, offering what the toolbar, More, the stars and the left pane's controls offer.

const menu = () => screen.findByRole('menu')
const items = async () => within(await menu()).getAllByRole('menuitem')
const names = async () => (await items()).map((item) => item.textContent)
const enabled = async () => (await items()).filter((item) => item.getAttribute('aria-disabled') !== 'true').map((item) => item.textContent)
const item = async (name: string | RegExp) => within(await menu()).findByRole('menuitem', { name })
// Read from the page itself: while a menu is open, the rest of the page is hidden from the accessibility tree
const selectedRows = () => Array.from(document.querySelectorAll('[data-row-key][aria-selected="true"]'), (one) => one.getAttribute('data-row-key'))
const pane = () => screen.getByRole('navigation', { name: 'Navigation' })

beforeEach(() => vi.mocked(launch).mockClear())

describe("a document row's menu", () => {
  it('offers the toolbar’s and More’s actions and Favorites, with Open in New Tab ending the open group', async () => {
    await renderApp('/folders/7', routes().all)
    const target = await row(/Lease/)
    expect(fireEvent.contextMenu(target)).toBe(false)

    expect(await screen.findByRole('menu', { name: 'Actions for Lease.docx' })).toBeTruthy()
    expect(await names()).toEqual(['Open', 'Preview', 'Open in New Tab', 'Download', 'Check Out', 'Rename', 'Move To…', 'Delete', 'Copy Link', 'Add to Favorites'])
    expect(within(await menu()).getAllByRole('separator')).toHaveLength(3)
    const newTab = await item('Open in New Tab')
    expect(newTab.getAttribute('href')).toBe('/app/doc/10432')
    expect(newTab.getAttribute('target')).toBe('_blank')
    await expectNoAxeViolations(document.body)
  })

  it('selects a row that isn’t selected first, in place of the selection, as Finder does', async () => {
    const { router } = await renderApp('/folders/7', routes().all)
    await select(/Letter/)
    fireEvent.contextMenu(await row(/Plan/))
    await menu()
    await waitFor(() => expect(router.state.location.search).toMatchObject({ sel: '10433' }))
    expect(selectedRows()).toEqual(['d10433'])
  })

  it('keeps a selection it’s inside, and then enables only what takes several: Move To… and Delete', async () => {
    await renderApp('/folders/7', routes().all)
    await select(/Lease/, /Plan/)
    fireEvent.contextMenu(await row(/Plan/))
    expect(await screen.findByRole('menu', { name: 'Actions for 2 documents' })).toBeTruthy()
    expect(selectedRows()).toEqual(['d10432', 'd10433'])
    expect(await enabled()).toEqual(['Move To…', 'Delete'])
    // As the toolbar decides: its Open is disabled too
    fireEvent.keyDown(await menu(), { key: 'Escape' })
    await waitFor(() => expect(screen.queryByRole('menu')).toBeNull())
    expect(within(await toolbar()).getByRole('button', { name: 'Open' }).hasAttribute('disabled')).toBe(true)
    expect(selectedRows()).toEqual(['d10432', 'd10433'])
  })

  it('moves the whole selection with Move To…', async () => {
    await renderApp('/folders/7', routes().all)
    await select(/Lease/, /Plan/)
    fireEvent.contextMenu(await row(/Lease/))
    await userEvent.click(await item('Move To…'))
    expect(await screen.findByRole('dialog', { name: 'Move 2 Documents' })).toBeTruthy()
  })

  it('renames in place from Rename, and the field keeps the browser’s own menu', async () => {
    const { written } = routes()
    await renderApp('/folders/7', routes().all)
    fireEvent.contextMenu(await row(/Letter/))
    await userEvent.click(await item('Rename'))
    const field = await screen.findByRole('textbox', { name: /New Name of Letter/ })
    await waitFor(() => expect(document.activeElement).toBe(field))
    expect(fireEvent.contextMenu(field)).toBe(true)
    expect(screen.queryByRole('menu')).toBeNull()
    expect(written.renamed).toEqual([])
  })

  it('renames a folder row in place from Rename', async () => {
    const { all, written } = routes()
    await renderApp('/folders/7', all)
    fireEvent.contextMenu(await row(/Drafts/))
    await userEvent.click(await item('Rename'))
    const field = await screen.findByRole('textbox', { name: 'New Name of Drafts' })
    await waitFor(() => expect(document.activeElement).toBe(field))
    await userEvent.keyboard('Final Drafts{Enter}')
    await waitFor(() => expect(written.renamedFolders).toEqual([{ id: 8, name: 'Final Drafts' }]))
  })

  it("greys Rename out in a folder row's menu where the server says it can't be renamed", async () => {
    const { all } = routes({ 'GET /v1/folders/7/children': () => children([{ ...drafts, canRename: false }]) })
    await renderApp('/folders/7', all)
    fireEvent.contextMenu(await row(/Drafts/))
    expect((await item('Rename')).getAttribute('aria-disabled')).toBe('true')
  })

  it('opens on Shift+F10 and on the menu key at the focused row, its first item focused, and Esc gives the row the focus back', async () => {
    await renderApp('/folders/7', routes().all)
    await select(/Plan/)
    const target = await row(/Plan/)
    target.focus()
    await userEvent.keyboard('{Shift>}{F10}{/Shift}')
    await waitFor(async () => expect(document.activeElement).toBe(await item('Open')))
    await userEvent.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByRole('menu')).toBeNull())
    await waitFor(() => expect(document.activeElement).toBe(target))

    fireEvent.keyDown(target, { key: 'ContextMenu' })
    expect(await screen.findByRole('menu', { name: 'Actions for Plan.pdf' })).toBeTruthy()
  })

  it('opens the document in its app from Open, as the toolbar does', async () => {
    await renderApp('/folders/7', routes().all)
    fireEvent.contextMenu(await row(/Letter/))
    await userEvent.click(await item('Open'))
    expect(launch).toHaveBeenCalledExactlyOnceWith(`healdan://open/${letter.docNumber}?server=${window.location.host}`)
  })

  it('offers Check In and Release for the person’s own web check-out, in place of Check Out', async () => {
    const mine = doc({ ...plan, hold: { kind: 'checkout', by: { id: 5, name: 'Priya Nair' }, since: '2026-11-03T10:00:00Z', device: null, heldOn: null } })
    const { all } = routes({ 'GET /v1/folders/7/children': children([letter, lease, mine]) })
    const { api } = await renderApp('/folders/7', { ...all, 'DELETE /v1/documents/10433/checkout': () => doc({ ...plan, hold: null }) })
    fireEvent.contextMenu(await row(/Plan/))
    expect(await names()).toEqual(['Open', 'Preview', 'Open in New Tab', 'Download', 'Check In', 'Release', 'Rename', 'Move To…', 'Delete', 'Copy Link', 'Add to Favorites'])
    await userEvent.click(await item('Release'))
    await waitFor(() => expect(api.callsTo('DELETE /v1/documents/10433/checkout')).toHaveLength(1))
  })

  it('checks in a file it asks for, of the document’s kind', async () => {
    const mine = doc({ ...plan, hold: { kind: 'checkout', by: { id: 5, name: 'Priya Nair' }, since: '2026-11-03T10:00:00Z', device: null, heldOn: null } })
    const { all } = routes({ 'GET /v1/folders/7/children': children([letter, lease, mine]) })
    const { api } = await renderApp('/folders/7', { ...all, 'POST /v1/documents/10433/versions': () => doc({ ...plan, hold: null }) })
    fireEvent.contextMenu(await row(/Plan/))
    await userEvent.click(await item('Check In'))
    const picker = await waitFor(() => {
      const found = document.querySelector<HTMLInputElement>('input[type="file"][aria-label="File to Check In"]')
      expect(found).toBeTruthy()
      return found!
    })
    expect(picker.accept).toBe('.pdf')
    await userEvent.upload(picker, new File(['x'], 'Plan.pdf', { type: 'application/pdf' }))
    await waitFor(() => expect(api.callsTo('POST /v1/documents/10433/versions')).toHaveLength(1))
    expect(document.querySelector('input[type="file"][aria-label="File to Check In"]')).toBeNull()
  })

  it('has Check Out disabled while the toolbar’s check-out of the document runs, as the toolbar’s is', async () => {
    let finish: (value: unknown) => void = () => {}
    const { all } = routes({ 'POST /v1/documents/10432/checkout': () => new Promise((resolve) => (finish = resolve)) })
    await renderApp('/folders/7?sel=10432', all)
    await row(/Lease/)
    await userEvent.click(within(await toolbar()).getByRole('button', { name: 'Check Out' }))
    await waitFor(async () => expect(within(await toolbar()).getByRole('button', { name: 'Check Out' }).hasAttribute('disabled')).toBe(true))
    fireEvent.contextMenu(await row(/Lease/))
    expect((await item('Check Out')).getAttribute('aria-disabled')).toBe('true')
    finish(doc({ ...lease, hold: { kind: 'checkout', by: { id: 5, name: 'Priya Nair' }, since: '2026-11-03T10:00:00Z', device: null, heldOn: null } }))
  })

  it('adds the document to Favorites', async () => {
    const { all } = routes({ 'POST /v1/me/favorites': undefined })
    const { api } = await renderApp('/folders/7', all)
    fireEvent.contextMenu(await row(/Lease/))
    await userEvent.click(await item('Add to Favorites'))
    await waitFor(() => expect(api.callsTo('POST /v1/me/favorites')).toHaveLength(1))
  })
})

describe('where the browser keeps its own menu', () => {
  it('in the list outside its rows: the column headings', async () => {
    await renderApp('/folders/7', routes().all)
    await row(/Lease/)
    expect(fireEvent.contextMenu(screen.getByRole('columnheader', { name: /Title/ }))).toBe(true)
    expect(screen.queryByRole('menu')).toBeNull()
  })

  it('in the search box and on the folder’s heading', async () => {
    await renderApp('/folders/7', routes().all)
    await row(/Lease/)
    expect(fireEvent.contextMenu(screen.getByRole('combobox', { name: /Search/ }))).toBe(true)
    expect(fireEvent.contextMenu(screen.getByRole('heading', { name: 'Correspondence' }))).toBe(true)
    expect(screen.queryByRole('menu')).toBeNull()
  })
})

describe("a folder row's menu", () => {
  it('offers Open, Open in New Tab, New Folder and Favorites, and leaves the selection alone', async () => {
    await renderApp('/folders/7', routes().all)
    await select(/Lease/)
    fireEvent.contextMenu(await row(/Drafts/))
    expect(await names()).toEqual(['Open', 'Open in New Tab', 'New Folder', 'Rename', 'Add to Favorites'])
    expect((await item('Open in New Tab')).getAttribute('href')).toBe('/app/folders/8')
    expect(selectedRows()).toEqual(['d10432'])
    await userEvent.click(await item('New Folder'))
    expect(await screen.findByRole('dialog', { name: 'New Folder' })).toBeTruthy()
  })

  it('has no New Folder for a search folder, as its toolbar has none', async () => {
    const recentHere = folder({ id: 9, name: 'Recent Here', kind: 'searchFolder' })
    const { all } = routes({ 'GET /v1/folders/7/children': children([recentHere, letter]) })
    await renderApp('/folders/7', all)
    fireEvent.contextMenu(await row(/Recent Here/))
    expect(await names()).toEqual(['Open', 'Open in New Tab', 'Rename', 'Add to Favorites'])
  })

  it('opens the folder', async () => {
    const { router } = await renderApp('/folders/7', routes().all)
    fireEvent.contextMenu(await row(/Drafts/))
    await userEvent.click(await item('Open'))
    await waitFor(() => expect(router.state.location.pathname).toBe('/folders/8'))
  })
})

describe("the left pane's menus", () => {
  it('My Favorites: Open, Open in New Tab and Remove from Favorites', async () => {
    const { api, router } = await renderApp('/folders/7', { ...routes().all, 'DELETE /v1/me/favorites/*': undefined })
    const link = await within(pane()).findByRole('link', { name: 'Cap Table.xlsx' })
    expect(fireEvent.contextMenu(link)).toBe(false)
    expect(await screen.findByRole('menu', { name: 'Actions for Cap Table.xlsx' })).toBeTruthy()
    expect(await names()).toEqual(['Open', 'Open in New Tab', 'Remove from Favorites'])
    expect((await item('Open in New Tab')).getAttribute('href')).toBe('/app/doc/10500')
    await userEvent.click(await item('Remove from Favorites'))
    await waitFor(() => expect(api.callsTo('DELETE /v1/me/favorites/901')).toHaveLength(1))

    fireEvent.contextMenu(within(pane()).getByRole('link', { name: 'Due Diligence' }))
    await userEvent.click(await item('Open'))
    await waitFor(() => expect(router.state.location.pathname).toBe('/folders/130'))
  })

  it('Recent Matters: Open, Open in New Tab, and Add to My Matters for one that isn’t in it', async () => {
    const { api } = await renderApp('/folders/7', { ...routes().all, 'PUT /v1/me/matters/*': undefined })
    fireEvent.contextMenu(await within(pane()).findByRole('link', { name: /1002\.1/ }))
    expect(await names()).toEqual(['Open', 'Open in New Tab', 'Rename', 'Add to My Matters'])
    expect((await item('Open in New Tab')).getAttribute('href')).toBe('/app/folders/120')
    await userEvent.click(await item('Add to My Matters'))
    await waitFor(() => expect(api.callsTo('PUT /v1/me/matters/120')).toHaveLength(1))
  })

  it('Recent Matters: Remove from My Matters for one that is', async () => {
    await renderApp('/folders/7', { ...routes().all, 'GET /v1/matters/recent': page([matter()]) })
    fireEvent.contextMenu(await within(pane()).findByRole('link', { name: /1001\.1/ }))
    expect(await names()).toEqual(['Open', 'Open in New Tab', 'Rename', 'Remove from My Matters'])
  })

  it('the tree: a matter’s menu, and its folders’, opened by keys too', async () => {
    const { api } = await renderApp('/folders/7', { ...routes().all, 'DELETE /v1/me/matters/*': undefined })
    const tree = await screen.findByRole('treegrid', { name: 'My Matters' })
    const matterRow = within(tree).getByRole('row', { name: /1001\.1/ })
    fireEvent.contextMenu(matterRow)
    expect(await names()).toEqual(['Open', 'Open in New Tab', 'Rename', 'Remove from My Matters'])
    await userEvent.click(await item('Remove from My Matters'))
    await waitFor(() => expect(api.callsTo('DELETE /v1/me/matters/110')).toHaveLength(1))

    fireEvent.click(within(tree).getByRole('button', { name: /expand/i }))
    const correspondence = await within(tree).findByRole('row', { name: /Correspondence/ })
    correspondence.focus()
    await userEvent.keyboard('{Shift>}{F10}{/Shift}')
    expect(await screen.findByRole('menu', { name: 'Actions for Correspondence' })).toBeTruthy()
    expect(await names()).toEqual(['Open', 'Open in New Tab', 'New Folder', 'Rename', 'Add to Favorites'])
    await userEvent.keyboard('{Escape}')
    await waitFor(() => expect(document.activeElement).toBe(correspondence))

    fireEvent.contextMenu(within(tree).getByRole('row', { name: /My Recent Docs Here/ }))
    expect(await names()).toEqual(['Open', 'Open in New Tab', 'Rename', 'Add to Favorites'])
  })

  it('keeps the browser menu on Recent’s links, which open a list of the app', async () => {
    await renderApp('/folders/7', routes().all)
    expect(fireEvent.contextMenu(within(pane()).getByRole('link', { name: /Emails/ }))).toBe(true)
  })

  it('works in the narrow layout, where Esc closes the menu and leaves the pane open', async () => {
    setViewport(768)
    await renderApp('/folders/7', routes().all)
    await userEvent.click(await screen.findByRole('button', { name: 'Navigation' }))
    const tree = await within(await screen.findByRole('navigation', { name: 'Navigation' })).findByRole('treegrid', { name: 'My Matters' })
    const matterRow = within(tree).getByRole('row', { name: /1001\.1/ })
    matterRow.focus()
    await userEvent.keyboard('{Shift>}{F10}{/Shift}')
    expect(await names()).toEqual(['Open', 'Open in New Tab', 'Rename', 'Remove from My Matters'])
    await userEvent.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByRole('menu')).toBeNull())
    expect(screen.getByRole('navigation', { name: 'Navigation' })).toBeTruthy()
    await waitFor(() => expect(document.activeElement).toBe(matterRow))
  })
})

describe('the other lists', () => {
  it('Recent Documents', async () => {
    await renderApp('/recent/documents', routes().all)
    const grid = await within(screen.getByRole('main')).findByRole('grid')
    fireEvent.contextMenu(await within(grid).findByRole('row', { name: /Letter from Dunmere/ }))
    expect(await names()).toEqual(['Open', 'Preview', 'Open in New Tab', 'Download', 'Check Out', 'Rename', 'Move To…', 'Delete', 'Copy Link', 'Add to Favorites'])
  })

  it('the # result', async () => {
    const shown = doc({ docNumber: 10388, name: 'Letter from Dunmere Pryce.pdf' })
    await renderApp('/doc/10388', { ...routes().all, 'GET /v1/lookup': { docNumber: 10388, version: '1.0' }, 'GET /v1/documents/10388': shown })
    const grid = await within(screen.getByRole('main')).findByRole('grid')
    fireEvent.contextMenu(await within(grid).findByRole('row', { name: /Letter from Dunmere/ }))
    expect(await screen.findByRole('menu', { name: 'Actions for Letter from Dunmere Pryce.pdf' })).toBeTruthy()
    expect(await enabled()).toContain('Rename')
  })

  it("a search's matters, which have a matter's menu", async () => {
    await renderApp('/search?scope=matters&q=lease', { ...routes().all, 'GET /v1/search': page([matter({ id: 12, folderId: 120, display: '1002.1 – Commercial Lease' })]) })
    const grid = await within(screen.getByRole('main')).findByRole('grid')
    fireEvent.contextMenu(await within(grid).findByRole('row', { name: /1002\.1/ }))
    expect(await names()).toEqual(['Open', 'Open in New Tab', 'Rename', 'Add to My Matters'])
    // A matter's own folder takes the matter's name: Rename is greyed out
    expect((await item('Rename')).getAttribute('aria-disabled')).toBe('true')
  })
})
