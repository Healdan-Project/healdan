// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { describe, expect, it } from 'vitest'

// jsdom can't compute what a style draws, so this reads each component's stylesheet: every rule for a focus-visible
// state must use the shared tokens, never a colour of its own.
const sheets = import.meta.glob<string>('./*.module.css', { query: '?raw', import: 'default', eager: true })

describe('focus style', () => {
  it('finds the components stylesheets', () => {
    expect(Object.keys(sheets).sort()).toEqual([
      './Button.module.css',
      './Choices.module.css',
      './ComboBox.module.css',
      './DatePicker.module.css',
      './Dialog.module.css',
      './Menu.module.css',
      './Table.module.css',
      './Toast.module.css',
      './Tree.module.css',
    ])
  })

  for (const [file, css] of Object.entries(sheets)) {
    it(`${file} uses the shared focus outline on focus-visible`, () => {
      const rules = [...css.matchAll(/([^{}]*\[data-focus-visible\][^{}]*)\{([^}]*)\}/g)]
      if (file === './Dialog.module.css') {
        expect(rules).toHaveLength(0) // the dialog holds focusable parts, not focus of its own
        return
      }
      expect(rules.length).toBeGreaterThan(0)
      if (file === './Table.module.css') {
        // Rows touch, and an outline set off outside them would be cut off by the box they scroll in
        for (const [, selector, body] of rules) expect(body, selector).toContain('box-shadow: var(--hd-focus-inset)')
        return
      }
      for (const [, selector, body] of rules) {
        expect(body, selector).toContain('outline: var(--hd-focus-outline)')
        expect(body, selector).toContain('outline-offset: var(--hd-focus-offset)')
      }
    })
  }

  it('lets a dialog taller than the viewport scroll', () => {
    expect(sheets['./Dialog.module.css']).toMatch(/max-height:\s*calc\(100dvh/)
    expect(sheets['./Dialog.module.css']).toMatch(/overflow:\s*auto/)
  })
})
