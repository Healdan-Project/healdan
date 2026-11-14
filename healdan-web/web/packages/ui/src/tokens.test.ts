// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { describe, expect, it } from 'vitest'
import css from './tokens.css?raw'
import buttonCss from './components/Button.module.css?raw'


function block(source: string): Record<string, string> {
  return Object.fromEntries([...source.matchAll(/(--[\w-]+):\s*([^;]+);/g)].map((m) => [m[1], m[2]!.trim()]))
}

// A token that is another's, `var(--hd-…)`, takes that one's value in the same scheme, as the browser does on :root.
function resolved(tokens: Record<string, string>): Record<string, string> {
  const valueOf = (value: string): string => {
    const other = /^var\((--[\w-]+)\)$/.exec(value)?.[1]
    return other && tokens[other] ? valueOf(tokens[other]) : value
  }
  return Object.fromEntries(Object.entries(tokens).map(([name, value]) => [name, valueOf(value)]))
}

const dark = block(css.slice(css.indexOf('@media (prefers-color-scheme: dark)')))
const lightOwn = block(css.slice(0, css.indexOf('@media (prefers-color-scheme: dark)')))
const light = resolved(lightOwn)
const darkAll = resolved({ ...lightOwn, ...dark })

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

// The icons that stand for a kind of item (icons.ts) are graphics, so they need the 3:1 of WCAG 1.4.11 on every surface
// one sits on: a list, a dialog or a popup (window), a hovered row (row-alt), the left pane and the scope picker
// (sidebar), the left pane's hovered rows (line), its open entry and a row a file is dragged over (pill), a selected row
// while the list hasn't the focus (selection-inactive), and the details' thumbnail (field). On the accent fill they take
// --hd-accent-on-fill, which PAIRS checks.
const KINDS = Object.keys(lightOwn).filter((name) => name.startsWith('--hd-kind-'))
const KIND_SURFACES = ['window', 'row-alt', 'sidebar', 'line', 'pill', 'selection-inactive', 'field']
const KIND_PAIRS = KINDS.flatMap((kind) => KIND_SURFACES.map((bg): [string, string] => [kind, `--hd-${bg}`]))

describe('tokens.css', () => {
  it('defines the row height and the same names for dark as light', () => {
    expect(light['--row-height']).toBe('34px')
    for (const name of Object.keys(dark)) expect(light).toHaveProperty([name])
  })

  // Light's colours are too dark for the dark theme: each kind has its own there, unless it is another token, which does
  it('gives every kind a colour of its own in dark, or one that follows another token', () => {
    expect(KINDS.length).toBeGreaterThan(0)
    for (const kind of KINDS) {
      if (!lightOwn[kind]!.startsWith('var(')) expect(dark).toHaveProperty([kind])
    }
  })

  for (const [scheme, tokens] of [['light', light], ['dark', darkAll]] as const) {
    it.each(PAIRS)(`${scheme}: %s on %s reaches AA`, (fg, bg) => {
      expect(contrast(tokens[fg]!, tokens[bg]!)).toBeGreaterThanOrEqual(4.5)
    })

    it.each(KIND_PAIRS)(`${scheme}: the kind colour %s shows on %s`, (fg, bg) => {
      expect(contrast(tokens[fg]!, tokens[bg]!)).toBeGreaterThanOrEqual(3)
    })

    it.each(FOCUS_SURFACES)(`${scheme}: the focus edge shows on --hd-%s`, (bg) => {
      expect(contrast(tokens['--hd-focus-edge']!, tokens[`--hd-${bg}`]!)).toBeGreaterThanOrEqual(3)
    })

    // Counts dim while they are asked for again, in the primary text colour (filters.module.css): still AA on every plain surface
    it.each(PLAIN)(`${scheme}: --hd-text at the busy opacity reaches AA on --hd-%s`, (bg) => {
      const opacity = Number(tokens['--hd-busy-opacity'])
      const mix = (a: string, b: string) => '#' + [1, 3, 5].map((i) => Math.round(parseInt(a.slice(i, i + 2), 16) * opacity + parseInt(b.slice(i, i + 2), 16) * (1 - opacity)).toString(16).padStart(2, '0')).join('')
      const background = tokens[`--hd-${bg}`]!
      expect(contrast(mix(tokens['--hd-text']!, background), background)).toBeGreaterThanOrEqual(4.5)
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
