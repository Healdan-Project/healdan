// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { describe, expect, it } from 'vitest'
import css from './tokens.css?raw'


function block(source: string): Record<string, string> {
  return Object.fromEntries([...source.matchAll(/(--[\w-]+):\s*([^;]+);/g)].map((m) => [m[1], m[2]!.trim()]))
}

const dark = block(css.slice(css.indexOf('@media (prefers-color-scheme: dark)')))
const light = block(css.slice(0, css.indexOf('@media (prefers-color-scheme: dark)')))
const darkAll = { ...light, ...dark }

function luminance(hex: string): number {
  const [r, g, b] = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255).map((c) => (c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4))
  return 0.2126 * r! + 0.7152 * g! + 0.0722 * b!
}

function contrast(a: string, b: string): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x)
  return (hi! + 0.05) / (lo! + 0.05)
}

// Text on a surface, both from the tokens, must reach WCAG AA (4.5:1).
const PAIRS: [string, string][] = [
  ['--hd-text', '--hd-window'],
  ['--hd-text', '--hd-sidebar'],
  ['--hd-text', '--hd-bar'],
  ['--hd-text', '--hd-row-alt'],
  ['--hd-text', '--hd-field'],
  ['--hd-text', '--hd-button-secondary'],
  ['--hd-text-secondary', '--hd-window'],
  ['--hd-text-secondary', '--hd-sidebar'],
  ['--hd-accent-on-fill', '--hd-accent-fill'],
  ['--hd-accent-text', '--hd-window'],
  ['--hd-accent-text', '--hd-sidebar'],
  ['--hd-yours', '--hd-window'],
  ['--hd-theirs', '--hd-window'],
  ['--hd-caution-text', '--hd-window'],
  ['--hd-required', '--hd-window'],
]

describe('tokens.css', () => {
  it('defines the row height and the same names for dark as light', () => {
    expect(light['--row-height']).toBe('34px')
    for (const name of Object.keys(dark)) expect(light).toHaveProperty([name])
  })

  for (const [scheme, tokens] of [['light', light], ['dark', darkAll]] as const) {
    it.each(PAIRS)(`${scheme}: %s on %s reaches AA`, (fg, bg) => {
      expect(contrast(tokens[fg]!, tokens[bg]!)).toBeGreaterThanOrEqual(4.5)
    })
  }
})
