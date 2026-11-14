// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { expectNoAxeViolations } from '../test/axe'
import { Menu, MenuItem } from './Menu'
import menuCss from './Menu.module.css?raw'

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

  it('draws an item that is a link as every other item: no underline, no pointer, the same colour', async () => {
    render(
      <Menu label="Help">
        <MenuItem id="guide" href="/guide">
          Guide
        </MenuItem>
        <MenuItem id="plain">Plain</MenuItem>
      </Menu>,
    )
    const trigger = screen.getByRole('button', { name: 'Help' })
    fireEvent.keyDown(trigger, { key: 'Enter' })
    fireEvent.keyUp(trigger, { key: 'Enter' })
    const link = await screen.findByRole('menuitem', { name: 'Guide' })
    expect(link.tagName).toBe('A')
    const linkStyle = getComputedStyle(link)
    const plainStyle = getComputedStyle(screen.getByRole('menuitem', { name: 'Plain' }))
    // jsdom underlines a link and gives it a pointer, as a browser does, unless the item's rule says otherwise
    expect(linkStyle.textDecorationLine).toBe('none')
    expect(linkStyle.cursor).toBe(plainStyle.cursor)
    expect(linkStyle.color).toBe(plainStyle.color)
    // jsdom has no :visited, so the rule is read: a visited link keeps the item's colour, however the rule is laid out
    expect(menuCss).toMatch(/\.item:visited\s*\{[^}]*\bcolor:\s*var\(--hd-text\)/)
  })
})
