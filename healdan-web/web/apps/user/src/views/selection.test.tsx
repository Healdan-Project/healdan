// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { act, fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { drafts, routes, row, select } from '../organising/testing'
import { comeBack } from '../test/comeBack'
import { children, document as doc, folder, matter, page } from '../test/fakeApi'
import { renderApp } from '../test/renderApp'
import { rowOf } from './listColumns'

// Folder rows select as documents' do (folders and Trash spec 7.5), so folders and documents move or delete together. Only
// one document is ever in the address, and a folder never is; the details stay a document's (plan correction 16).

const selectedRows = () => Array.from(document.querySelectorAll('[data-row-key][aria-selected="true"]'), (one) => one.getAttribute('data-row-key'))
const details = () => screen.queryByRole('region', { name: 'Details' })

async function addWithCmd(name: RegExp) {
  const user = userEvent.setup()
  // Cmd on a Mac; jsdom isn't one, so React Aria takes Ctrl there
  await user.keyboard('{Control>}')
  await user.click(within(await row(name)).getByRole('rowheader'))
  await user.keyboard('{/Control}')
}

describe('The rows of a list', () => {
  it('select documents and folders, never matters, and a click opens a folder', () => {
    expect(rowOf(doc()).selectable).toBe(true)
    expect(rowOf(drafts)).toMatchObject({ selectable: true, opensOnClick: true })
    expect(rowOf(matter()).selectable).toBe(false)
  })
})

describe('Selecting folder rows', () => {
  it('selects a folder with the documents by Shift- or Cmd-click, and keeps the selection out of the address', async () => {
    const { router } = await renderApp('/folders/7', routes().all)
    await select(/Lease/)
    await waitFor(() => expect(router.history.location.search).toBe('?sel=10432'))
    expect(await screen.findByRole('region', { name: 'Details' })).toBeTruthy()

    await addWithCmd(/Drafts/)
    await waitFor(() => expect(router.history.location.search).toBe(''))
    expect(selectedRows()).toEqual(['f8', 'd10432'])
    // The details are a document's: none shows for a folder, nor for several
    await waitFor(() => expect(details()).toBeNull())

    const user = userEvent.setup()
    await user.keyboard('{Shift>}')
    await user.click(within(await row(/Plan/)).getByRole('rowheader'))
    await user.keyboard('{/Shift}')
    expect(selectedRows()).toEqual(['f8', 'd10431', 'd10432', 'd10433'])
    expect(router.history.location.search).toBe('')
  })

  it('selects a folder alone with Space, which opens no Quick Look, and shows no details for it', async () => {
    const { router } = await renderApp('/folders/7?sel=10432', routes().all)
    expect(await screen.findByRole('region', { name: 'Details' })).toBeTruthy()
    const folderRow = await row(/Drafts/)
    act(() => folderRow.focus())
    await userEvent.keyboard(' ')
    await waitFor(() => expect(selectedRows()).toEqual(['f8']))
    await waitFor(() => expect(router.history.location.search).toBe(''))
    expect(screen.queryByRole('dialog')).toBeNull()
    await waitFor(() => expect(details()).toBeNull())
  })

  it('selects a folder the arrows move onto, as they select a document', async () => {
    const { router } = await renderApp('/folders/7?sel=10431', routes().all)
    const letterRow = await row(/Letter to Dunmere/)
    act(() => letterRow.focus())
    fireEvent.keyDown(letterRow, { key: 'ArrowUp' })
    fireEvent.keyUp(letterRow, { key: 'ArrowUp' })
    await waitFor(() => expect(selectedRows()).toEqual(['f8']))
    await waitFor(() => expect(router.history.location.search).toBe(''))
  })

  it('opens a folder on a plain click, as before, and selects nothing', async () => {
    const { router } = await renderApp('/folders/7', routes().all)
    await userEvent.click(within(await row(/Drafts/)).getByRole('rowheader'))
    await waitFor(() => expect(router.history.location.pathname).toBe('/app/folders/8'))
  })

  it('keeps the selected folder while it is listed, and forgets it once it is gone', async () => {
    const { all } = routes()
    let subfolders = [drafts]
    await renderApp('/folders/7', { ...all, 'GET /v1/folders/7/children': () => children(subfolders) })
    const folderRow = await row(/Drafts/)
    act(() => folderRow.focus())
    await userEvent.keyboard(' ')
    await waitFor(() => expect(selectedRows()).toEqual(['f8']))
    subfolders = []
    // Coming back to the window asks for the list again
    comeBack()
    await waitFor(() => expect(screen.queryByRole('row', { name: /Drafts/ })).toBeNull())
    expect(selectedRows()).toEqual([])
  })

  it('selects folders in Recent › Folders, where Delete then asks about them', async () => {
    const pleadings = folder({ id: 112, name: 'Pleadings' })
    await renderApp('/recent/folders', { ...routes().all, 'GET /v1/folders/recent': page([drafts, pleadings]) })
    const grid = await within(screen.getByRole('main')).findByRole('grid')
    const first = await within(grid).findByRole('row', { name: /Drafts/ })
    act(() => first.focus())
    fireEvent.keyDown(first, { key: 'ArrowDown' })
    fireEvent.keyUp(first, { key: 'ArrowDown' })
    await waitFor(() => expect(selectedRows()).toEqual(['f112']))
    await userEvent.keyboard('{Delete}')
    expect(await screen.findByRole('dialog', { name: 'Move "Pleadings" to the Trash?' })).toBeTruthy()
  })
})
