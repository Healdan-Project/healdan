// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { ApiError } from '@healdan/api'
import { expectNoAxeViolations } from '../test/axe'
import { document as doc, folder, matter } from '../test/fakeApi'
import { renderApp } from '../test/renderApp'
import { routes, select, toolbar } from './testing'

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
    expect(within(dialog).getByRole('textbox', { name: 'Find a Matter' })).toBeTruthy()
    await expectNoAxeViolations(dialog)
    // No Store is asked for: a Store's id is 2
    expect(api.callsTo('GET /v1/folders/2')).toHaveLength(0)
  })

  it('moves the selection into a folder of the chosen matter, each document with its own call', async () => {
    const { all, written } = routes()
    await renderApp('/folders/7', all)
    await select(/Lease/, /Plan/)
    const dialog = await openPicker()
    await userEvent.click(await within(dialog).findByRole('button', { name: /1001\.1 – Charter Dispute/ }))

    // A matter's top level takes no documents; a search folder isn't offered
    expect(await within(dialog).findByRole('button', { name: /Correspondence/ })).toBeTruthy()
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
    await userEvent.click(await within(dialog).findByRole('button', { name: /1003\.3 – Harbour Lease/ }))
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
