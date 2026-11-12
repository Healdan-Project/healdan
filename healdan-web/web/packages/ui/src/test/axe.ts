// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import axe from 'axe-core'
import { expect } from 'vitest'

// Fails the test, listing each rule broken, when the rendered markup has an accessibility violation. A frame's own page isn't
// looked into (axe can't reach into jsdom's frames, which hold no page there anyway); the frame element itself is checked.
export async function expectNoAxeViolations(container: Element): Promise<void> {
  const result = await axe.run(container, { iframes: false, rules: { 'color-contrast': { enabled: false } } })
  const found = result.violations.map((v) => `${v.id}: ${v.nodes.map((n) => n.html).join(' | ')}`)
  expect(found).toEqual([])
}
