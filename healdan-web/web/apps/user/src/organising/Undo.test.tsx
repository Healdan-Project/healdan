// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { act, fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { expectNoAxeViolations } from '../test/axe'
import { fakeApi, problem, trashItem } from '../test/fakeApi'
import { renderApp } from '../test/renderApp'
import { routes, row, select } from './testing'
import { undoAction } from './undo'

// Undo, on the toast every delete says itself with (folders and Trash spec 7.4; the plan's Review Focus 5): it restores what
// that delete moved to the Trash, back where it was, and says what the world, moved on meanwhile, didn't let it.

const toastOf = async (text: string) => (await screen.findByText(text)).closest('[role="alertdialog"]') as HTMLElement
const undoIn = async (text: string) => within(await toastOf(text)).getByRole('button', { name: 'Undo' })

/** Focuses Drafts' row and deletes it, after the question */
async function deleteDrafts() {
  const target = await row(/Drafts/)
  act(() => target.focus())
  await userEvent.keyboard('{Delete}')
  await userEvent.click(within(await screen.findByRole('dialog', { name: 'Move "Drafts" to the Trash?' })).getByRole('button', { name: 'Move to Trash' }))
}

describe('Undo', () => {
  it('restores the Trash item the delete made, back where it was, then says so, and the row comes back', async () => {
    const { all, written } = routes()
    await renderApp('/folders/7', all)
    await deleteDrafts()
    await waitFor(() => expect(screen.queryByRole('row', { name: /Drafts/ })).toBeNull())
    await userEvent.click(await undoIn('Moved "Drafts" to the Trash'))

    // No folderId: back where it was
    await waitFor(() => expect(written.restored).toEqual([{ id: 700, body: undefined }]))
    expect(await screen.findByText('Restored "Drafts"')).toBeTruthy()
    expect(await row(/Drafts/)).toBeTruthy()
  })

  it('restores each item of a delete of several, one call each, and counts them', async () => {
    const { all, written } = routes()
    await renderApp('/folders/7', all)
    await select(/Lease/, /Plan/)
    await userEvent.keyboard('{Delete}')
    await userEvent.click(within(await screen.findByRole('dialog', { name: 'Move 2 items to the Trash?' })).getByRole('button', { name: 'Move to Trash' }))
    await userEvent.click(await undoIn('Moved 2 items to the Trash'))

    await waitFor(() => expect(written.restored.map((one) => one.id)).toEqual([700, 701]))
    expect(await screen.findByText('Restored 2 items')).toBeTruthy()
  })

  it('calls restore once though Undo is pressed twice', async () => {
    const { all, written } = routes()
    await renderApp('/folders/7', all)
    await deleteDrafts()
    const undo = await undoIn('Moved "Drafts" to the Trash')
    // Both presses before the toast has gone
    fireEvent.click(undo)
    fireEvent.click(undo)
    expect(await screen.findByText('Restored "Drafts"')).toBeTruthy()
    expect(written.restored).toHaveLength(1)
  })

  it('takes a second press of the same action as none, however it comes, as two restores of one item aren\'t serialised', async () => {
    let answer: (value: unknown) => void = () => {}
    const api = fakeApi({ 'POST /v1/trash/*/restore': () => new Promise((resolve) => (answer = resolve)) })
    const item = trashItem({ id: 700, name: 'Drafts' })
    const refuse = vi.fn()
    const action = undoAction(api, [item], { refresh: () => Promise.resolve(), refuse })
    expect(action.label).toBe('Undo')
    action.onAction()
    action.onAction()
    answer({ place: { id: 7 }, item })
    await waitFor(() => expect(api.callsTo('POST /v1/trash/*/restore')).toHaveLength(1))
    action.onAction()
    await act(() => new Promise((resolve) => setTimeout(resolve, 20)))
    expect(api.callsTo('POST /v1/trash/*/restore')).toHaveLength(1)
    expect(refuse).not.toHaveBeenCalled()
  })

  it("says which items weren't restored, and why, and restores the rest", async () => {
    const { all, written } = routes({
      // Someone restored it from the Trash already
      'POST /v1/trash/700/restore': problem(404, 'not-found'),
      // Its folder was deleted meanwhile
      'POST /v1/trash/701/restore': problem(409, 'place-gone'),
    })
    const { api } = await renderApp('/folders/7', all)
    await select(/Lease/, /Plan/)
    const user = userEvent.setup()
    await user.keyboard('{Control>}')
    await user.click(within(await row(/Drafts/)).getByRole('rowheader'))
    await user.keyboard('{/Control}')
    await userEvent.keyboard('{Delete}')
    await userEvent.click(within(await screen.findByRole('dialog', { name: 'Move 3 items to the Trash?' })).getByRole('button', { name: 'Move to Trash' }))
    await userEvent.click(await undoIn('Moved 3 items to the Trash'))

    const dialog = await screen.findByRole('dialog', { name: "Some Items Weren't Restored" })
    expect(within(dialog).getAllByRole('listitem').map((one) => one.textContent)).toEqual([
      "Drafts: Not found, or you can't see it.",
      "Lease.docx: The folder it was deleted from is gone, or you can't restore into it. Choose another.",
    ])
    expect(screen.queryByText(/server sentence/)).toBeNull()
    expect(api.callsTo('POST /v1/trash/*/restore').map((call) => call.path)).toEqual(['/v1/trash/700/restore', '/v1/trash/701/restore', '/v1/trash/702/restore'])
    expect(written.restored.map((one) => one.id)).toEqual([702])
    expect(await screen.findByText('Restored "Plan.pdf"')).toBeTruthy()
    await expectNoAxeViolations(dialog)
  })
})
