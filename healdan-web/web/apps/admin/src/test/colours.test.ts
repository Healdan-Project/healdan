// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { describe, expect, it } from 'vitest'

// jsdom draws no style, and axe can't check contrast in it, so the app's stylesheets are read instead: every colour is
// one of @healdan/ui's tokens, in pairs that packages/ui's tokens.test.ts checks at AA in the light theme and the dark one.
// Each text colour here reaches AA on every plain surface, so any of them may sit on any of those. The surfaces that hold
// --hd-text only (tokens.css) may be used where the same rule sets that colour; and a bar's track and fill hold no text.
const TEXT = ['text', 'text-secondary', 'accent-text', 'yours', 'theirs', 'caution-text', 'required'].map((name) => `--hd-${name}`)
const PLAIN = ['window', 'bar', 'sidebar', 'row-alt', 'field', 'field-readonly'].map((name) => `--hd-${name}`)
const TEXT_ONLY = ['pill', 'selection-inactive', 'button-secondary', 'line'].map((name) => `--hd-${name}`)
// What draws no text: a bar's track and fill, and the chart's bars
const NO_TEXT = /^\.(track|fill|bar|baseline)\b/

const sheets = import.meta.glob<string>('../**/*.module.css', { query: '?raw', import: 'default', eager: true })

interface Rule {
  file: string
  selector: string
  body: string
}

function rules(): Rule[] {
  return Object.entries(sheets).flatMap(([file, css]) =>
    [...css.replace(/\/\*[\s\S]*?\*\//g, '').matchAll(/([^{}@]+)\{([^{}]*)\}/g)].map(([, selector, body]) => ({ file, selector: selector!.trim(), body: body! })),
  )
}

const token = (body: string, property: string) => new RegExp(`(?:^|;|\\s)${property}:\\s*var\\((--[\\w-]+)\\)`).exec(body)?.[1]

describe("The Admin app's stylesheets", () => {
  it('are there to read', () => {
    expect(Object.keys(sheets).length).toBeGreaterThanOrEqual(5)
  })

  it('name no colour of their own, only tokens', () => {
    for (const { file, selector, body } of rules()) {
      expect([file, selector, body.match(/#[0-9a-f]{3,8}\b|rgba?\(|hsla?\(/gi)]).toEqual([file, selector, null])
    }
  })

  it('give text only the colours that reach AA on every plain surface', () => {
    for (const { file, selector, body } of rules()) {
      const color = token(body, 'color')
      if (color) expect([file, selector, TEXT.includes(color)]).toEqual([file, selector, true])
    }
  })

  it('put text only on plain surfaces, or on a surface for --hd-text with that colour set in the same rule', () => {
    for (const { file, selector, body } of rules()) {
      const background = token(body, 'background') ?? token(body, 'background-color')
      if (!background || NO_TEXT.test(selector)) continue
      const fits = PLAIN.includes(background) || (TEXT_ONLY.includes(background) && token(body, 'color') === '--hd-text')
      expect([file, selector, background, fits]).toEqual([file, selector, background, true])
    }
  })
})
