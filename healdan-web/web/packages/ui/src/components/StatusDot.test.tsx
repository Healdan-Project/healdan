// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { expectNoAxeViolations } from '../test/axe'
import '../tokens.css'
import { StatusDot, type Status } from './StatusDot'
import statusCss from './StatusDot.module.css?raw'

const statuses: [Status, string][] = [
  ['ok', 'Index Healthy'],
  ['attention', '3 Files Stuck'],
  ['off', 'Disabled'],
]

/** The declarations of the rule for exactly this selector */
function rule(selector: string): string {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  return new RegExp(`(?:^|\\})\\s*${escaped}\\s*\\{([^}]*)\\}`).exec(statusCss.replace(/\/\*[\s\S]*?\*\//g, ''))?.[1] ?? ''
}

describe('StatusDot', () => {
  it.each(statuses)('says %s in words, which are the name of what it is in, and the dot is decoration', async (status, words) => {
    const { container } = render(
      <button type="button">
        <StatusDot status={status}>{words}</StatusDot>
      </button>,
    )
    expect(screen.getByRole('button', { name: words })).toBeTruthy()
    const dot = container.querySelector('[data-dot]')!
    expect(dot.getAttribute('aria-hidden')).toBe('true')
    expect(dot.textContent).toBe('')
    expect(container.querySelector(`[data-status="${status}"]`)).toBeTruthy()
    await expectNoAxeViolations(container)
  })

  // jsdom draws no style, so the stylesheet is read: each status's dot and words take colours that tokens.test.ts checks
  // in both themes, the words at AA on every plain surface and the dot at 3:1
  it.each([
    ['ok', '--hd-yours', undefined],
    ['attention', '--hd-caution', '--hd-caution-text'],
    ['off', '--hd-text-secondary', '--hd-text-secondary'],
  ] as const)('draws %s in tokens that pass in both themes', (status, dot, words) => {
    expect(rule(`.status[data-status='${status}'] .dot`)).toMatch(new RegExp(`background:\\s*var\\(${dot}\\)`))
    const color = /color:\s*var\((--[\w-]+)\)/.exec(rule(`.status[data-status='${status}']`))?.[1]
    expect(color).toBe(words)
  })

  it('leaves its words in the colour of a selected row, as only that passes there', () => {
    expect(statusCss).toMatch(/\[data-selected\]\s+\.status\s*\{[^}]*color:\s*inherit/)
  })
})
