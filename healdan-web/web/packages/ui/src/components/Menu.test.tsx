// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { expectNoAxeViolations } from '../test/axe'
import { Menu, MenuItem } from './Menu'

function setup(onAction = vi.fn()) {
  const view = render(
    <Menu label="Actions">
      <MenuItem id="open" onAction={onAction}>Open</MenuItem>
      <MenuItem id="rename">Rename</MenuItem>
    </Menu>,
  )
  return { ...view, onAction }
}

describe('Menu', () => {
  it('opens with the keyboard, runs an item with Enter and closes', async () => {
    const { onAction } = setup()
    const trigger = screen.getByRole('button', { name: 'Actions' })
    trigger.focus()
    expect(document.activeElement).toBe(trigger)
    fireEvent.keyDown(trigger, { key: 'Enter' })
    fireEvent.keyUp(trigger, { key: 'Enter' })
    const item = await screen.findByRole('menuitem', { name: 'Open' })
    expect(screen.getByRole("menu")).toBeTruthy()
    fireEvent.keyDown(item, { key: 'Enter' })
    fireEvent.keyUp(item, { key: 'Enter' })
    expect(onAction).toHaveBeenCalledTimes(1)
    expect(screen.queryByRole('menu')).toBeNull()
  })

  it('closes on Escape and gives focus back to its button', async () => {
    setup()
    const trigger = screen.getByRole('button', { name: 'Actions' })
    trigger.focus()
    fireEvent.keyDown(trigger, { key: 'Enter' })
    fireEvent.keyUp(trigger, { key: 'Enter' })
    const menu = await screen.findByRole('menu')
    fireEvent.keyDown(menu, { key: 'Escape' })
    fireEvent.keyUp(menu, { key: 'Escape' })
    expect(screen.queryByRole('menu')).toBeNull()
    await waitFor(() => expect(document.activeElement).toBe(trigger))
  })

  it('can be disabled, so its button opens nothing', () => {
    render(
      <Menu label="Actions" isDisabled>
        <MenuItem id="one">One</MenuItem>
      </Menu>,
    )
    const trigger = screen.getByRole('button', { name: 'Actions' })
    expect(trigger.hasAttribute('disabled')).toBe(true)
    fireEvent.click(trigger)
    expect(screen.queryByRole('menu')).toBeNull()
  })

  it('passes axe, closed and open', async () => {
    const { container } = setup()
    await expectNoAxeViolations(container)
    const trigger = screen.getByRole('button', { name: 'Actions' })
    fireEvent.keyDown(trigger, { key: 'Enter' })
    fireEvent.keyUp(trigger, { key: 'Enter' })
    await screen.findByRole('menu')
    await expectNoAxeViolations(document.body)
  })
})
