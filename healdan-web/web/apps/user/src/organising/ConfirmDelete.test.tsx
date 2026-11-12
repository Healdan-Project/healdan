// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { expectNoAxeViolations } from '../test/axe'
import { ConfirmDelete } from './ConfirmDelete'

const documents = [
  { docNumber: 1, name: 'A <b>bold</b>.docx' },
  { docNumber: 2, name: 'B.pdf' },
]

describe('ConfirmDelete', () => {
  it('asks the question with the count, names every document as text, and has Move to Trash focused', async () => {
    render(<ConfirmDelete documents={documents} onConfirm={() => {}} onCancel={() => {}} />)
    const dialog = await screen.findByRole('dialog', { name: 'Move 2 documents to the Trash?' })
    expect(screen.getByText('A <b>bold</b>.docx')).toBeTruthy()
    expect(dialog.querySelector('b')).toBeNull()
    expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Move to Trash' }))
    await expectNoAxeViolations(dialog)
  })

  it('confirms with the button and cancels with Esc', async () => {
    const onConfirm = vi.fn()
    const onCancel = vi.fn()
    render(<ConfirmDelete documents={documents.slice(1)} onConfirm={onConfirm} onCancel={onCancel} />)
    expect(await screen.findByRole('dialog', { name: 'Move 1 document to the Trash?' })).toBeTruthy()
    await userEvent.keyboard('{Escape}')
    expect(onCancel).toHaveBeenCalled()
    expect(onConfirm).not.toHaveBeenCalled()
    await userEvent.click(screen.getByRole('button', { name: 'Move to Trash' }))
    expect(onConfirm).toHaveBeenCalledOnce()
  })
})
