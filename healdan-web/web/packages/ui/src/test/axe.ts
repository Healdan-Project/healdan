// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import axe from 'axe-core'
import { expect } from 'vitest'

// Fails the test, listing each rule broken, when the rendered markup has an accessibility violation. A frame's own page isn't
// looked into (axe can't reach into jsdom's frames, which hold no page there anyway); the frame element itself is checked.
// Given the document, axe also runs its rules for the whole page (landmarks, the title, the language).
export async function expectNoAxeViolations(container: Element | Document): Promise<void> {
  const result = await axe.run(container, { iframes: false, rules: { 'color-contrast': { enabled: false } } })
  const found = result.violations.map((v) => `${v.id}: ${v.nodes.map((n) => n.html).join(' | ')}`)
  expect(found).toEqual([])
}

// The same over a whole page, or the part of it a dialog's layer holds: jsdom's page has no title or language of its own,
// which the apps' index.html give theirs, so it has them while axe looks
export async function expectNoPageAxeViolations(context: Document | Element, title: string): Promise<void> {
  const before = { title: document.title, lang: document.documentElement.lang }
  document.title = title
  document.documentElement.lang = 'en-US'
  try {
    await expectNoAxeViolations(context)
  } finally {
    document.title = before.title
    document.documentElement.lang = before.lang
  }
}

// What carries aria-hidden, to say so when anything does
export const hiddenOnes = (inside: ParentNode = document) => [...inside.querySelectorAll('[aria-hidden="true"]')].map((element) => element.outerHTML.slice(0, 80))
