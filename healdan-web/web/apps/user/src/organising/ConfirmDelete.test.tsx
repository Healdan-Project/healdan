// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { expectNoAxeViolations } from '../test/axe'
import { ConfirmDelete } from './ConfirmDelete'

const documents = [
  { docNumber: 1, name: 'A <b>bold</b>.docx' },
  { docNumber: 2, name: 'B.pdf' },
]
const oldDrafts = { id: 8, name: 'Old Drafts', parentId: 7, matterId: 11, canMove: true, canDelete: true }
const none = { documents: [], folders: [] }

describe('ConfirmDelete', () => {
  it('asks the question with the count, names every item as text, folders first, and has Move to Trash focused', async () => {
    render(<ConfirmDelete items={{ documents, folders: [oldDrafts] }} onConfirm={() => {}} onCancel={() => {}} />)
    const dialog = await screen.findByRole('dialog', { name: 'Move 3 items to the Trash?' })
    expect(within(dialog).getAllByRole('listitem').map((item) => item.textContent)).toEqual(['Old Drafts', 'A <b>bold</b>.docx', 'B.pdf'])
    expect(dialog.querySelector('b')).toBeNull()
    expect(within(dialog).getByText('You can restore them from the Trash, at the foot of the left pane.')).toBeTruthy()
    expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Move to Trash' }))
    await expectNoAxeViolations(dialog)
  })

  it('names the one folder it asks about, says what it holds that the person can read, then the note', async () => {
    render(<ConfirmDelete items={{ ...none, folders: [oldDrafts] }} contents={{ folders: 3, documents: 11 }} onConfirm={() => {}} onCancel={() => {}} />)
    const dialog = await screen.findByRole('dialog', { name: 'Move "Old Drafts" to the Trash?' })
    expect(Array.from(dialog.querySelectorAll('p'), (line) => line.textContent)).toEqual(['It holds 3 folders and 11 documents.', 'You can restore it from the Trash, at the foot of the left pane.'])
    expect(within(dialog).queryByRole('listitem')).toBeNull()
    await expectNoAxeViolations(dialog)
  })

  it('says nothing of what an empty folder holds, nor of what has yet to come', async () => {
    const { rerender } = render(<ConfirmDelete items={{ ...none, folders: [oldDrafts] }} onConfirm={() => {}} onCancel={() => {}} />)
    const dialog = await screen.findByRole('dialog', { name: 'Move "Old Drafts" to the Trash?' })
    expect(within(dialog).queryByText(/It holds/)).toBeNull()
    rerender(<ConfirmDelete items={{ ...none, folders: [oldDrafts] }} contents={{ folders: 0, documents: 0 }} onConfirm={() => {}} onCancel={() => {}} />)
    expect(within(dialog).queryByText(/It holds/)).toBeNull()
  })

  it('confirms with the button and cancels with Esc', async () => {
    const onConfirm = vi.fn()
    const onCancel = vi.fn()
    render(<ConfirmDelete items={{ ...none, documents: documents.slice(1) }} onConfirm={onConfirm} onCancel={onCancel} />)
    expect(await screen.findByRole('dialog', { name: 'Move "B.pdf" to the Trash?' })).toBeTruthy()
    await userEvent.keyboard('{Escape}')
    expect(onCancel).toHaveBeenCalled()
    expect(onConfirm).not.toHaveBeenCalled()
    await userEvent.click(screen.getByRole('button', { name: 'Move to Trash' }))
    expect(onConfirm).toHaveBeenCalledOnce()
  })
})
