// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { render, screen } from '@testing-library/react'
import { fireEvent } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { expectNoAxeViolations } from '../test/axe'
import { Button } from './Button'
import buttonCss from './Button.module.css?raw'

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

  it('while pending keeps the focus and takes no press, and looks as a disabled one does', () => {
    const onPress = vi.fn()
    render(
      <>
        <Button isPending onPress={onPress}>Save</Button>
      </>,
    )
    const pending = screen.getByRole('button', { name: 'Save' })
    pending.focus()
    expect(document.activeElement).toBe(pending)
    fireEvent.keyDown(pending, { key: 'Enter' })
    fireEvent.keyUp(pending, { key: 'Enter' })
    expect(onPress).not.toHaveBeenCalled()
    // jsdom leaves the colour tokens unresolved, so the rules are read: pending takes disabled's muted text and face
    expect(buttonCss).toMatch(/\.button\[data-disabled\],\s*\.button\[data-pending\]\s*\{[^}]*\bcolor:\s*var\(--hd-text-muted\)/)
    expect(buttonCss).toMatch(/\.button\.default\[data-disabled\],\s*\.button\.default\[data-pending\]\s*\{/)
  })

  it('has a quiet face for an icon in a row, named by its label', () => {
    render(<Button variant="quiet" aria-label="Add to Favorites">★</Button>)
    const button = screen.getByRole('button', { name: 'Add to Favorites' })
    expect(button.className).toContain('quiet')
  })

  it('passes axe', async () => {
    const { container } = render(<><Button>Cancel</Button><Button variant="default">Save</Button><Button variant="quiet" aria-label="More">⋯</Button></>)
    await expectNoAxeViolations(container)
  })
})
