// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { ApiError } from '@healdan/api'
import { expectNoAxeViolations } from '../test/axe'
import { renderApp } from '../test/renderApp'
import { heldByJamie } from '../filing/testing'
import { children, document as doc } from '../test/fakeApi'
import { inFolder } from '../filing/testing'
import { routes, row, select, toolbar } from './testing'

const only = doc({ docNumber: 10450, name: 'Only.docx', folder: inFolder })

describe('Deleting', () => {
  it('asks "Move 2 documents to the Trash?" naming each, and says the Trash is in the Classic Interface', async () => {
    const { all, written } = routes()
    await renderApp('/folders/7', all)
    // Plan first, then Lease: the question lists them in the list's order
    await select(/Plan/, /Lease/)
    await userEvent.keyboard('{Delete}')

    const dialog = await screen.findByRole('dialog', { name: 'Move 2 documents to the Trash?' })
    expect(within(dialog).getAllByRole('listitem').map((item) => item.textContent)).toEqual(['Lease.docx', 'Plan.pdf'])
    expect(within(dialog).getByText('The Trash is in the Classic Interface, where they can be restored.')).toBeTruthy()
    await expectNoAxeViolations(dialog)
    expect(written.deleted).toEqual([])

    await userEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(written.deleted).toEqual([])
  })

  it('moves each document to the trash with its own call once confirmed, and the list asks again', async () => {
    const { all, written } = routes()
    const { api } = await renderApp('/folders/7', all)
    await select(/Lease/, /Plan/)
    await userEvent.keyboard('{Delete}')
    await userEvent.click(await screen.findByRole('button', { name: 'Move to Trash' }))

    await waitFor(() => expect(written.deleted).toEqual([10432, 10433]))
    expect(await screen.findByText('Moved 2 documents to the Trash')).toBeTruthy()
    const grid = await within(screen.getByRole('main')).findByRole('grid')
    await waitFor(() => expect(within(grid).queryByRole('row', { name: /Lease/ })).toBeNull())
    expect(api.callsTo('DELETE /v1/documents/*')).toHaveLength(2)
  })

  it('forgets the selection of documents it deleted, so F2 renames the row the focus is on', async () => {
    const { all, written } = routes()
    await renderApp('/folders/7', all)
    await select(/Letter/, /Lease/)
    await userEvent.keyboard('{Delete}')
    await userEvent.click(await screen.findByRole('button', { name: 'Move to Trash' }))
    await waitFor(() => expect(document.activeElement?.getAttribute('data-row-key')).toBe('d10433'))
    await userEvent.keyboard('{F2}')
    expect(screen.queryByText('Select one document to rename it.')).toBeNull()
    await userEvent.keyboard('Plan Two{Enter}')
    await waitFor(() => expect(written.renamed).toEqual([{ docNumber: 10433, name: 'Plan Two.pdf' }]))
  })

  it('Enter confirms, as the Trash can be restored from', async () => {
    const { all, written } = routes()
    await renderApp('/folders/7', all)
    await select(/Plan/)
    await userEvent.keyboard('{Delete}')
    expect(await screen.findByRole('dialog', { name: 'Move 1 document to the Trash?' })).toBeTruthy()
    await userEvent.keyboard('{Enter}')
    await waitFor(() => expect(written.deleted).toEqual([10433]))
  })

  it('More › Delete does the same, and drops the document from the address, whose details would answer 404', async () => {
    const { all, written } = routes()
    const { router } = await renderApp('/folders/7?sel=10433', all)
    await row(/Plan/)
    await userEvent.click(within(await toolbar()).getByRole('button', { name: 'More' }))
    await userEvent.click(await screen.findByRole('menuitem', { name: 'Delete' }))
    await userEvent.click(await screen.findByRole('button', { name: 'Move to Trash' }))
    await waitFor(() => expect(written.deleted).toEqual([10433]))
    await waitFor(() => expect(router.history.location.search).toBe(''))
  })

  it('says which documents were refused, and why, while the others are deleted', async () => {
    const { all, written } = routes({
      'DELETE /v1/documents/10432': new ApiError(409, 'checked-out', 'A server sentence that must not be shown raw.', {
        code: 'checked-out',
        hold: heldByJamie,
      }),
    })
    await renderApp('/folders/7', all)
    await select(/Lease/, /Plan/)
    await userEvent.keyboard('{Delete}')
    await userEvent.click(await screen.findByRole('button', { name: 'Move to Trash' }))

    const dialog = await screen.findByRole('dialog', { name: "Some Documents Weren't Moved to the Trash" })
    const [line] = within(dialog).getAllByRole('listitem')
    expect(line!.textContent).toMatch(/^Lease\.docx: It's checked out\. Checked out by Jamie Clarke since .*released on the computer/)
    expect(screen.queryByText(/server sentence/)).toBeNull()
    // The other was deleted all the same
    expect(written.deleted).toEqual([10433])
    expect(await screen.findByText('Moved 1 document to the Trash')).toBeTruthy()
    await expectNoAxeViolations(dialog)
  })

  describe('puts the focus', () => {
    const focused = () => document.activeElement?.getAttribute('data-row-key')

    it('on the next row after Delete, whichever of the list is deleted', async () => {
      const { all } = routes()
      await renderApp('/folders/7', all)
      await select(/Lease/)
      await userEvent.keyboard('{Delete}')
      await userEvent.click(await screen.findByRole('button', { name: 'Move to Trash' }))
      await waitFor(() => expect(focused()).toBe('d10433'))
    })

    it('on the row before it, when the last row is deleted', async () => {
      const { all } = routes()
      await renderApp('/folders/7', all)
      await select(/Plan/)
      await userEvent.keyboard('{Delete}')
      await userEvent.click(await screen.findByRole('button', { name: 'Move to Trash' }))
      await waitFor(() => expect(focused()).toBe('d10432'))
    })

    it('on the next row after More > Delete too', async () => {
      const { all } = routes()
      await renderApp('/folders/7', all)
      await select(/Lease/)
      await userEvent.click(within(await toolbar()).getByRole('button', { name: 'More' }))
      await userEvent.click(await screen.findByRole('menuitem', { name: 'Delete' }))
      await userEvent.click(await screen.findByRole('button', { name: 'Move to Trash' }))
      await waitFor(() => expect(focused()).toBe('d10433'))
    })

    it('on the list when nothing is left in it', async () => {
      let left = [only]
      const { all } = routes({ 'GET /v1/folders/7/children': () => children(left), 'DELETE /v1/documents/*': () => void (left = []) })
      await renderApp('/folders/7', all)
      await select(/Only/)
      await userEvent.keyboard('{Delete}')
      await userEvent.click(await screen.findByRole('button', { name: 'Move to Trash' }))
      await waitFor(() => expect(document.activeElement?.getAttribute('role')).toBe('grid'))
    })
  })
})
