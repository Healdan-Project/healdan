// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { render, screen } from '@testing-library/react'
import { fireEvent } from '@testing-library/react'
import { describe, expect, it, onTestFinished, vi } from 'vitest'
import { expectNoAxeViolations } from '../test/axe'
import buttonCss from './Button.module.css?raw'
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

  it('while pending keeps the focus and takes no press', () => {
    const onPress = vi.fn()
    render(<Button isPending onPress={onPress}>Save</Button>)
    const pending = screen.getByRole('button', { name: 'Save' })
    pending.focus()
    expect(document.activeElement).toBe(pending)
    fireEvent.keyDown(pending, { key: 'Enter' })
    fireEvent.keyUp(pending, { key: 'Enter' })
    expect(onPress).not.toHaveBeenCalled()
  })

  it('while pending looks as a disabled one does, in each face', () => {
    // The module's rules are in the page (vite.config.ts), so what jsdom applies to each state is compared, not their text.
    // Each colour token gets a value of its own, so a state that took another token would look different.
    const tokens = document.head.appendChild(document.createElement('style'))
    tokens.textContent = `:root { ${['text', 'text-muted', 'line', 'accent-fill', 'accent-on-fill', 'button-secondary', 'button-secondary-line'].map((name, i) => `--hd-${name}: rgb(${i + 1}, 0, 0);`).join(' ')} }`
    onTestFinished(() => tokens.remove())
    const look = (button: HTMLElement) => {
      const style = getComputedStyle(button)
      return { color: style.color, background: style.backgroundColor, border: style.borderColor }
    }
    for (const variant of ['default', 'secondary', 'quiet'] as const) {
      const { unmount } = render(
        <>
          <Button variant={variant} aria-label="Enabled">Enabled</Button>
          <Button variant={variant} isDisabled aria-label="Disabled">Disabled</Button>
          <Button variant={variant} isPending aria-label="Pending">Pending</Button>
        </>,
      )
      const [enabled, disabled, pending] = screen.getAllByRole('button')
      expect(look(pending!), variant).toEqual(look(disabled!))
      expect(look(pending!), variant).not.toEqual(look(enabled!))
      unmount()
    }
  })

  it('while pending, with a label of its own, says it is working', async () => {
    const { container } = render(<Button variant="quiet" isPending aria-label="Add to Favorites">★</Button>)
    expect(screen.getByRole('button', { name: 'Add to Favorites Working…' })).toBeTruthy()
    await expectNoAxeViolations(container)
  })

  it('has a quiet face for an icon in a row, named by its label', () => {
    render(<Button variant="quiet" aria-label="Add to Favorites">★</Button>)
    const button = screen.getByRole('button', { name: 'Add to Favorites' })
    expect(button.className).toContain('quiet')
  })

  it('the danger button has its own face', () => {
    render(<Button variant="danger">Release</Button>)
    const button = screen.getByRole('button', { name: 'Release' })
    expect(button.className).toContain('danger')
    expect(button.className).not.toContain('default')
    expect(buttonCss).toMatch(/\.button:not\(\.default\):not\(\.danger\)\[aria-pressed='true'\]/)
  })

  it('passes axe', async () => {
    const { container } = render(<><Button>Cancel</Button><Button variant="default">Save</Button><Button variant="quiet" aria-label="More">⋯</Button></>)
    await expectNoAxeViolations(container)
  })
})
