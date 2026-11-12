// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { render, screen } from '@testing-library/react'
import { fireEvent } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { expectNoAxeViolations } from '../test/axe'
import { Button } from './Button'

describe('Button', () => {
  it('is reachable by Tab and works with Enter and Space', () => {
    const onPress = vi.fn()
    render(<Button onPress={onPress}>Save</Button>)
    const button = screen.getByRole('button', { name: 'Save' })
    button.focus()
    expect(document.activeElement).toBe(button)
    fireEvent.keyDown(button, { key: 'Enter' })
    fireEvent.keyUp(button, { key: 'Enter' })
    expect(onPress).toHaveBeenCalledTimes(1)
    fireEvent.keyDown(button, { key: ' ' })
    fireEvent.keyUp(button, { key: ' ' })
    expect(onPress).toHaveBeenCalledTimes(2)
  })

  it('does nothing when disabled', () => {
    const onPress = vi.fn()
    render(<Button isDisabled onPress={onPress}>Save</Button>)
    fireEvent.click(screen.getByRole('button'))
    expect(onPress).not.toHaveBeenCalled()
  })

  it('passes axe', async () => {
    const { container } = render(<><Button>Cancel</Button><Button variant="default">Save</Button></>)
    await expectNoAxeViolations(container)
  })
})
