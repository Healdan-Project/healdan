// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { describe, expect, it } from 'vitest'
import html from '../index.html?raw'
import css from '../public/pressable.css?raw'
// eslint-disable-next-line no-restricted-imports -- reads react-aria's source as text, to check it; no component is imported
import usePress from '../../../node_modules/react-aria/dist/private/interactions/usePress.mjs?raw'

const squash = (text: string) => text.replace(/\s+/g, ' ').trim()

// React Aria injects a <style> for pressable elements, which the page's Content-Security-Policy blocks; the page links a
// file with the same rule under the id the injection looks for. An upgrade that changes either shows here.
describe('the pressable style', () => {
  it('uses the id and the rule that react-aria injects', () => {
    const id = /STYLE_ID = '([^']+)'/.exec(usePress)?.[1]
    expect(id).toBeTruthy()
    expect(html).toContain(`id="${id}"`)
    const attribute = /PRESSABLE_ATTRIBUTE = '([^']+)'/.exec(usePress)?.[1] ?? ''
    const rule = (/style\.textContent = `([^`]+)`/.exec(usePress)?.[1] ?? '').replace(/\$\{[^}]*PRESSABLE_ATTRIBUTE\}/g, attribute)
    expect(squash(css.replace(/\/\*[\s\S]*?\*\//g, ''))).toBe(squash(rule))
  })
})
