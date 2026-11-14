// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { expectNoAxeViolations } from '../test/axe'
import { ContextMenu } from './ContextMenu'
import { MenuItem, MenuSeparator } from './Menu'

function setup({ onAction = vi.fn(), onOpen = vi.fn() } = {}) {
  const view = render(
    <ContextMenu<string>
      target="[data-key]"
      // "none" stands for an item that has no menu of its own
      subjectOf={(element) => (element.dataset.key === 'none' ? undefined : element.dataset.key)}
      label={(key) => `Actions for ${key}`}
      onOpen={onOpen}
      items={(key) => (
        <>
          <MenuItem id="open" onAction={() => onAction('open', key)}>
            Open {key}
          </MenuItem>
          <MenuSeparator />
          <MenuItem id="rename" isDisabled={key === 'beta'} onAction={() => onAction('rename', key)}>
            Rename
          </MenuItem>
        </>
      )}
    >
      <ul aria-label="Things">
        <li tabIndex={-1} data-key="alpha">
          Alpha <span>inside</span>
        </li>
        <li tabIndex={-1} data-key="beta">
          Beta
        </li>
        <li tabIndex={-1} data-key="none">
          No menu
        </li>
        <li>
          <input aria-label="Name" />
        </li>
      </ul>
      <p>Space around</p>
    </ContextMenu>,
  )
  return { ...view, onAction, onOpen }
}

const item = (key: string) => document.querySelector<HTMLElement>(`[data-key="${key}"]`)!

describe('ContextMenu', () => {
  it('opens on a right-click in place of the browser menu, for the item around what was clicked', async () => {
    const { onOpen, onAction } = setup()
    const allowed = fireEvent.contextMenu(screen.getByText('inside'), { clientX: 40, clientY: 30 })
    expect(allowed).toBe(false)
    const menu = await screen.findByRole('menu', { name: 'Actions for alpha' })
    expect(onOpen).toHaveBeenCalledExactlyOnceWith('alpha')
    expect(within(menu).getAllByRole('menuitem').map((one) => one.textContent)).toEqual(['Open alpha', 'Rename'])
    expect(within(menu).getByRole('separator')).toBeTruthy()
    fireEvent.click(within(menu).getByRole('menuitem', { name: 'Open alpha' }))
    expect(onAction).toHaveBeenCalledExactlyOnceWith('open', 'alpha')
    await waitFor(() => expect(screen.queryByRole('menu')).toBeNull())
  })

  it('draws the items for the item asked about, and their disabled state', async () => {
    setup()
    fireEvent.contextMenu(item('beta'))
    const menu = await screen.findByRole('menu', { name: 'Actions for beta' })
    expect(within(menu).getByRole('menuitem', { name: 'Rename' }).getAttribute('aria-disabled')).toBe('true')
  })

  it('opens on Shift+F10 at the focused item, with its first item focused, and Esc gives the focus back', async () => {
    setup()
    const alpha = item('alpha')
    alpha.focus()
    fireEvent.keyDown(alpha, { key: 'F10', shiftKey: true })
    const menu = await screen.findByRole('menu', { name: 'Actions for alpha' })
    await waitFor(() => expect(document.activeElement).toBe(within(menu).getByRole('menuitem', { name: 'Open alpha' })))
    fireEvent.keyDown(document.activeElement!, { key: 'Escape' })
    fireEvent.keyUp(document.activeElement!, { key: 'Escape' })
    await waitFor(() => expect(screen.queryByRole('menu')).toBeNull())
    await waitFor(() => expect(document.activeElement).toBe(alpha))
  })

  it('opens on the menu key, and the browser’s contextmenu event that follows the key opens nothing more', async () => {
    const { onOpen } = setup()
    const beta = item('beta')
    beta.focus()
    fireEvent.keyDown(beta, { key: 'ContextMenu' })
    await screen.findByRole('menu', { name: 'Actions for beta' })
    expect(fireEvent.contextMenu(beta)).toBe(false)
    expect(screen.getAllByRole('menu')).toHaveLength(1)
    expect(onOpen).toHaveBeenCalledTimes(1)
  })

  it('gives the focus to the right-clicked item, so Esc leaves it there', async () => {
    setup()
    const beta = item('beta')
    fireEvent.contextMenu(beta)
    const menu = await screen.findByRole('menu')
    fireEvent.keyDown(menu, { key: 'Escape' })
    fireEvent.keyUp(menu, { key: 'Escape' })
    await waitFor(() => expect(screen.queryByRole('menu')).toBeNull())
    await waitFor(() => expect(document.activeElement).toBe(beta))
  })

  it('leaves the browser menu alone in a field, outside the items, and on an item with no menu', () => {
    const { onOpen } = setup()
    expect(fireEvent.contextMenu(screen.getByRole('textbox', { name: 'Name' }))).toBe(true)
    expect(fireEvent.contextMenu(screen.getByText('Space around'))).toBe(true)
    expect(fireEvent.contextMenu(item('none'))).toBe(true)
    const field = screen.getByRole('textbox', { name: 'Name' })
    field.focus()
    expect(fireEvent.keyDown(field, { key: 'F10', shiftKey: true })).toBe(true)
    expect(screen.queryByRole('menu')).toBeNull()
    expect(onOpen).not.toHaveBeenCalled()
  })

  it('takes F10 without Shift, and Shift+F10 with another modifier, as other keys', () => {
    setup()
    const alpha = item('alpha')
    alpha.focus()
    expect(fireEvent.keyDown(alpha, { key: 'F10' })).toBe(true)
    expect(fireEvent.keyDown(alpha, { key: 'F10', shiftKey: true, metaKey: true })).toBe(true)
    expect(screen.queryByRole('menu')).toBeNull()
  })

  it('closes on a right-click on the page outside it, which then has the browser\u2019s own menu, as React Aria\u2019s do', async () => {
    setup()
    fireEvent.contextMenu(item('alpha'))
    await screen.findByRole('menu')
    // Outside the open menu everything is inert, so a real browser's right-click lands on the body
    fireEvent.mouseDown(document.body, { button: 2 })
    await waitFor(() => expect(screen.queryByRole('menu')).toBeNull())
    expect(fireEvent.contextMenu(document.body)).toBe(true)
  })

  it('stays open for a right-click inside it', async () => {
    setup()
    fireEvent.contextMenu(item('alpha'))
    const menu = await screen.findByRole('menu')
    fireEvent.mouseDown(within(menu).getByRole('menuitem', { name: 'Rename' }), { button: 2 })
    expect(fireEvent.contextMenu(within(menu).getByRole('menuitem', { name: 'Rename' }))).toBe(false)
    expect(screen.getByRole('menu')).toBeTruthy()
  })

  it('closes on a right-click on the layer under it, with no browser menu over the page', async () => {
    setup()
    fireEvent.contextMenu(item('alpha'))
    await screen.findByRole('menu')
    expect(fireEvent.contextMenu(screen.getByTestId('underlay'))).toBe(false)
    await waitFor(() => expect(screen.queryByRole('menu')).toBeNull())
  })

  it('closes when what it acts on has gone, rather than staying open empty', async () => {
    const menuFor = (gone: boolean) => (
      <ContextMenu<string> target="li" subjectOf={() => 'row'} label={() => 'Actions'} items={() => (gone ? undefined : <MenuItem id="open">Open</MenuItem>)}>
        <ul>
          <li tabIndex={-1}>Row</li>
        </ul>
      </ContextMenu>
    )
    const { rerender } = render(menuFor(false))
    fireEvent.contextMenu(screen.getByText('Row'))
    expect(await screen.findByRole('menu', { name: 'Actions' })).toBeTruthy()
    // A refetch drops the row while the menu is open
    rerender(menuFor(true))
    await waitFor(() => expect(screen.queryByRole('menu')).toBeNull())
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('passes axe, open', async () => {
    setup()
    fireEvent.contextMenu(item('alpha'))
    await screen.findByRole('menu')
    await expectNoAxeViolations(document.body)
  })

  it('leaves the items of a ContextMenu inside it to that one', async () => {
    const outer = vi.fn()
    render(
      <ContextMenu<string> target="[data-key]" subjectOf={(element) => element.dataset.key} label={() => 'Outer'} onOpen={outer} items={() => <MenuItem id="o">Outer item</MenuItem>}>
        <div data-key="all">
          <ContextMenu<string> target="li" subjectOf={() => 'inner'} label={() => 'Inner'} items={() => <MenuItem id="i">Inner item</MenuItem>}>
            <ul>
              <li tabIndex={-1}>Inside</li>
            </ul>
          </ContextMenu>
        </div>
      </ContextMenu>,
    )
    const inside = screen.getByText('Inside')
    fireEvent.contextMenu(inside)
    expect(await screen.findByRole('menu', { name: 'Inner' })).toBeTruthy()
    expect(screen.getAllByRole('menu')).toHaveLength(1)
    expect(outer).not.toHaveBeenCalled()
  })
})
