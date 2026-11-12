// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it } from 'vitest'
import { expectNoAxeViolations } from '../test/axe'
import { Button } from './Button'
import { Dialog } from './Dialog'

function Example() {
  const [open, setOpen] = useState(false)
  return (
    <>
      <Button onPress={() => setOpen(true)}>Rename</Button>
      <Dialog title="Rename Folder" isOpen={open} onOpenChange={setOpen}>
        {(close) => (
          <>
            <input aria-label="Name" />
            <Button onPress={close}>Cancel</Button>
          </>
        )}
      </Dialog>
    </>
  )
}

function open() {
  const trigger = screen.getByRole('button', { name: 'Rename' })
  trigger.focus()
  fireEvent.keyDown(trigger, { key: 'Enter' })
  fireEvent.keyUp(trigger, { key: 'Enter' })
  return trigger
}

describe('Dialog', () => {
  it('opens with the keyboard, names itself and moves focus inside', async () => {
    render(<Example />)
    open()
    const dialog = await screen.findByRole('dialog', { name: 'Rename Folder' })
    expect(dialog.contains(document.activeElement)).toBe(true)
  })

  it('keeps Tab and Shift+Tab inside, wrapping at the ends', async () => {
    const user = userEvent.setup()
    render(<Example />)
    open()
    const dialog = await screen.findByRole('dialog')
    const [first, last] = [screen.getByLabelText('Name'), screen.getByRole('button', { name: 'Cancel' })]
    last.focus()
    await user.tab()
    expect(document.activeElement).toBe(first)
    await user.tab({ shift: true })
    expect(document.activeElement).toBe(last)
    expect(dialog.contains(document.activeElement)).toBe(true)
  })

  it('closes on Escape and returns focus', async () => {
    render(<Example />)
    const trigger = open()
    const dialog = await screen.findByRole('dialog')
    fireEvent.keyDown(dialog, { key: 'Escape' })
    fireEvent.keyUp(dialog, { key: 'Escape' })
    expect(screen.queryByRole('dialog')).toBeNull()
    await waitFor(() => expect(document.activeElement).toBe(trigger))
  })

  it('closes from a button inside it', async () => {
    render(<Example />)
    open()
    fireEvent.click(await screen.findByRole('button', { name: 'Cancel' }))
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('passes axe', async () => {
    render(<Example />)
    open()
    await screen.findByRole('dialog')
    await expectNoAxeViolations(document.body)
  })
})
