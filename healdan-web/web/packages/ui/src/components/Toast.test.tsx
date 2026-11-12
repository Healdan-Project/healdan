// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { act, fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { expectNoAxeViolations } from '../test/axe'
import { afterEach } from 'vitest'
import { clearToasts, toast, ToastRegion } from './Toast'

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

  it('passes axe', async () => {
    const { container } = render(<ToastRegion />)
    act(() => toast('Moved 2 documents'))
    await screen.findByRole('status')
    await expectNoAxeViolations(container)
  })
})
