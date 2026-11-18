// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { act, fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { expectNoAxeViolations } from '../test/axe'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach } from 'vitest'
import { clearToasts, toast, toastWithAction, ToastRegion } from './Toast'

describe('Toast', () => {
  afterEach(() => act(clearToasts))

  it('announces a message as a status and can be dismissed from the keyboard', async () => {
    render(<ToastRegion />)
    act(() => toast('Saved'))
    const status = await screen.findByRole('status')
    expect(status.textContent).toBe('Saved')
    const dismiss = screen.getByRole('button', { name: 'Dismiss' })
    dismiss.focus()
    expect(document.activeElement).toBe(dismiss)
    fireEvent.keyDown(dismiss, { key: 'Enter' })
    fireEvent.keyUp(dismiss, { key: 'Enter' })
    expect(screen.queryByRole('status')).toBeNull()
  })

  it('draws the action after the message, and pressing it calls it once and closes the toast', async () => {
    const onAction = vi.fn()
    render(<ToastRegion />)
    act(() => toastWithAction('Moved "Lease.pdf" to the Trash', { label: 'Undo', onAction }))
    const status = await screen.findByRole('status')
    const undo = screen.getByRole('button', { name: 'Undo' })
    expect(status.compareDocumentPosition(undo) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    fireEvent.click(undo)
    expect(onAction).toHaveBeenCalledTimes(1)
    expect(screen.queryByRole('status')).toBeNull()
  })

  it('is reached by F6, then Tab to the action, then Enter', async () => {
    const onAction = vi.fn()
    render(
      <>
        <button>Page</button>
        <ToastRegion />
      </>,
    )
    act(() => toastWithAction('Moved to the Trash', { label: 'Undo', onAction }))
    await screen.findByRole('status')
    screen.getByRole('button', { name: 'Page' }).focus()
    await userEvent.keyboard('{F6}')
    expect(screen.getByRole('region').contains(document.activeElement)).toBe(true)
    const undo = screen.getByRole('button', { name: 'Undo' })
    for (let i = 0; i < 4 && document.activeElement !== undo; i++) await userEvent.tab()
    expect(document.activeElement).toBe(undo)
    await userEvent.keyboard('{Enter}')
    expect(onAction).toHaveBeenCalledTimes(1)
  })

  it('returns focus to the page control it came from after the action is pressed', async () => {
    render(
      <>
        <button>Page</button>
        <ToastRegion />
      </>,
    )
    act(() => toastWithAction('Moved to the Trash', { label: 'Undo', onAction: vi.fn() }))
    await screen.findByRole('status')
    const page = screen.getByRole('button', { name: 'Page' })
    page.focus()
    await userEvent.keyboard('{F6}')
    const undo = screen.getByRole('button', { name: 'Undo' })
    for (let i = 0; i < 4 && document.activeElement !== undo; i++) await userEvent.tab()
    await userEvent.keyboard('{Enter}')
    expect(screen.queryByRole('status')).toBeNull()
    expect(document.activeElement).toBe(page)
  })

  it('shows a second toast after the first has closed, and F6 still reaches it', async () => {
    render(
      <>
        <button>Page</button>
        <ToastRegion />
      </>,
    )
    act(() => toastWithAction('First', { label: 'Undo', onAction: vi.fn() }))
    fireEvent.click(await screen.findByRole('button', { name: 'Undo' }))
    expect(screen.queryByRole('status')).toBeNull()
    act(() => toastWithAction('Second', { label: 'Undo', onAction: vi.fn() }))
    await screen.findByRole('status')
    screen.getByRole('button', { name: 'Page' }).focus()
    await userEvent.keyboard('{F6}')
    expect(screen.getByRole('region').contains(document.activeElement)).toBe(true)
  })

  describe('timing', () => {
    beforeEach(() => vi.useFakeTimers())
    afterEach(() => vi.useRealTimers())

    it('closes itself after 10 seconds', () => {
      render(<ToastRegion />)
      act(() => toastWithAction('Moved to the Trash', { label: 'Undo', onAction: vi.fn() }))
      expect(screen.queryByRole('status')).not.toBeNull()
      act(() => vi.advanceTimersByTime(9000))
      expect(screen.queryByRole('status')).not.toBeNull()
      act(() => vi.advanceTimersByTime(1500))
      expect(screen.queryByRole('status')).toBeNull()
    })

    it('stays while the pointer is on the region', () => {
      render(<ToastRegion />)
      act(() => toastWithAction('Moved to the Trash', { label: 'Undo', onAction: vi.fn() }))
      const region = screen.getByRole('region')
      fireEvent.pointerEnter(region)
      fireEvent.mouseEnter(region)
      act(() => vi.advanceTimersByTime(30000))
      expect(screen.queryByRole('status')).not.toBeNull()
      fireEvent.pointerLeave(region)
      fireEvent.mouseLeave(region)
      act(() => vi.advanceTimersByTime(11000))
      expect(screen.queryByRole('status')).toBeNull()
    })

    it('stays while focus is in the region', () => {
      render(<ToastRegion />)
      act(() => toastWithAction('Moved to the Trash', { label: 'Undo', onAction: vi.fn() }))
      act(() => screen.getByRole('button', { name: 'Undo' }).focus())
      act(() => vi.advanceTimersByTime(30000))
      expect(screen.queryByRole('status')).not.toBeNull()
    })
  })

  it('shows at most three toasts', async () => {
    render(<ToastRegion />)
    act(() => {
      for (let i = 1; i <= 5; i++) toastWithAction(`Moved ${i}`, { label: 'Undo', onAction: vi.fn() })
    })
    await screen.findAllByRole('status')
    expect(screen.getAllByRole('status')).toHaveLength(3)
  })

  it('passes axe with an action', async () => {
    const { container } = render(<ToastRegion />)
    act(() => toastWithAction('Moved to the Trash', { label: 'Undo', onAction: vi.fn() }))
    await screen.findByRole('status')
    await expectNoAxeViolations(container)
  })

  it('passes axe', async () => {
    const { container } = render(<ToastRegion />)
    act(() => toast('Moved 2 documents'))
    await screen.findByRole('status')
    await expectNoAxeViolations(container)
  })
})
