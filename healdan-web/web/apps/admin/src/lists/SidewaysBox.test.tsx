// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { expectNoAxeViolations } from '../test/axe'
import { SidewaysBox } from './SidewaysBox'

/**
 * jsdom has no ResizeObserver and lays nothing out: the box is given its widths, and its observers are called back by
 * hand, as a browser calls them when the box or its table changes size
 */
function observedSizes() {
  const observers: (() => void)[] = []
  vi.stubGlobal(
    'ResizeObserver',
    class {
      constructor(callback: () => void) {
        observers.push(callback)
      }
      observe() {}
      disconnect() {}
    },
  )
  return (box: HTMLElement, holds: number) => {
    Object.defineProperty(box, 'clientWidth', { configurable: true, value: 700 })
    Object.defineProperty(box, 'scrollWidth', { configurable: true, value: holds })
    act(() => observers.forEach((observer) => observer()))
  }
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('SidewaysBox', () => {
  it('is a named region and a Tab stop while what it holds is wider than it, and neither once it fits', async () => {
    const resize = observedSizes()
    const { container } = render(
      <>
        <button type="button">Before</button>
        <SidewaysBox name="Index Events (Scrolls Sideways)">
          <table aria-label="Index Events">
            <tbody>
              <tr>
                <td>A wide row</td>
              </tr>
            </tbody>
          </table>
        </SidewaysBox>
      </>,
    )
    const box = container.querySelector('table')!.parentElement!
    expect(screen.queryByRole('region')).toBeNull()
    resize(box, 1100)
    const region = screen.getByRole('region', { name: 'Index Events (Scrolls Sideways)' })
    expect(region).toBe(box)
    await userEvent.click(screen.getByRole('button', { name: 'Before' }))
    await userEvent.tab()
    expect(document.activeElement).toBe(box)
    await expectNoAxeViolations(container)
    resize(box, 700)
    expect(screen.queryByRole('region')).toBeNull()
    expect(box.hasAttribute('tabindex')).toBe(false)
  })
})
