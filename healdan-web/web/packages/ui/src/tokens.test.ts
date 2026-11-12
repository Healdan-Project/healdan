// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { describe, expect, it } from 'vitest'
import css from './tokens.css?raw'
import buttonCss from './components/Button.module.css?raw'


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

// Which text colours may sit on which surfaces. Some surfaces hold --hd-text only (documented in tokens.css); the other text colours fail AA there, so they are not allowed and the test doesn't ask them to pass.
const PLAIN = ['window', 'bar', 'sidebar', 'row-alt', 'field', 'field-readonly']
const TEXT = ['text', 'text-secondary', 'accent-text', 'yours', 'theirs', 'caution-text', 'required']
// Pill, Inactive selection and the secondary button's face hold --hd-text only.
const TEXT_ONLY = ['pill', 'selection-inactive', 'button-secondary']

const PAIRS: [string, string][] = [
  ...TEXT.flatMap((fg) => PLAIN.map((bg): [string, string] => [`--hd-${fg}`, `--hd-${bg}`])),
  ...TEXT_ONLY.map((bg): [string, string] => ['--hd-text', `--hd-${bg}`]),
  ['--hd-accent-on-fill', '--hd-accent-fill'],
]

// The focus edge shows against every surface the outline can touch (it is set off from the control, so the accent
// fill never touches it), at the 3:1 that non-text contrast asks for.
const FOCUS_SURFACES = [...PLAIN, ...TEXT_ONLY]

describe('tokens.css', () => {
  it('defines the row height and the same names for dark as light', () => {
    expect(light['--row-height']).toBe('34px')
    for (const name of Object.keys(dark)) expect(light).toHaveProperty([name])
  })

  for (const [scheme, tokens] of [['light', light], ['dark', darkAll]] as const) {
    it.each(PAIRS)(`${scheme}: %s on %s reaches AA`, (fg, bg) => {
      expect(contrast(tokens[fg]!, tokens[bg]!)).toBeGreaterThanOrEqual(4.5)
    })

    it.each(FOCUS_SURFACES)(`${scheme}: the focus edge shows on --hd-%s`, (bg) => {
      expect(contrast(tokens['--hd-focus-edge']!, tokens[`--hd-${bg}`]!)).toBeGreaterThanOrEqual(3)
    })

    // The filled star keeps the helper's amber, which is too pale on light surfaces to show by itself: its edge does
    // (WCAG 1.4.11's 3:1 for what shows a control's state).
    it.each(PLAIN)(`${scheme}: the filled star's edge shows on --hd-%s`, (bg) => {
      expect(contrast(tokens['--hd-star-edge']!, tokens[`--hd-${bg}`]!)).toBeGreaterThanOrEqual(3)
    })

    // A pressed button's face is pale, so its state shows by an edge too, 3:1 against the surface and the face
    it(`${scheme}: a pressed button's edge shows on the window and on its face`, () => {
      expect(contrast(tokens['--hd-pressed-edge']!, tokens['--hd-window']!)).toBeGreaterThanOrEqual(3)
      expect(contrast(tokens['--hd-pressed-edge']!, tokens['--hd-pressed']!)).toBeGreaterThanOrEqual(3)
      expect(contrast(tokens['--hd-text']!, tokens['--hd-pressed']!)).toBeGreaterThanOrEqual(4.5)
    })
  }

  it('gives only the plain and quiet buttons a pressed face, never the accent one', () => {
    expect(buttonCss).toMatch(/\.button:not\(\.default\)\[aria-pressed='true'\]\s*\{(?=[^}]*var\(--hd-pressed\))(?=[^}]*var\(--hd-pressed-edge\))/)
  })
})
