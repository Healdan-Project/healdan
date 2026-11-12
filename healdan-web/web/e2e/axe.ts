// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { readFileSync } from 'node:fs'
import { createRequire } from 'node:module'
import { expect, type Page } from '@playwright/test'

// axe-core is the workspace's own development dependency (the Vitest checks use it); nothing is added for the browser tests.
// The page's policy (AppServlet's) allows no inline script, so a script tag with the source in it, which is what
// `addScriptTag({ content })` makes, is blocked. `page.evaluate` runs the source through the browser's debugging protocol,
// which a page's policy doesn't govern, so the policy stays as the app ships it and no spec turns it off.
const source = readFileSync(createRequire(import.meta.url).resolve('axe-core/axe.min.js'), 'utf-8')

interface Violation {
  id: string
  impact: string | null
  help: string
  nodes: { target: unknown[]; html: string; failureSummary?: string }[]
}

/** Runs axe on the page, or on one part of it, and fails with each rule broken and where, in the colours the page is in now */
export async function expectNoViolations(page: Page, what: string, within?: string) {
  // Once per page load: a navigation that loads the page again needs it again
  if (!(await page.evaluate(() => 'axe' in window))) await page.evaluate(source)
  const violations = await page.evaluate(
    async (selector) => {
      const axe = (window as unknown as { axe: { run: (context: unknown, options: unknown) => Promise<{ violations: Violation[] }> } }).axe
      // A frame holding a sender's page can't be reached (it is sandboxed without a script): the frame element itself is checked
      const result = await axe.run(selector ? { include: [selector] } : document, { iframes: false })
      return result.violations.map((one) => ({
        id: one.id,
        impact: one.impact,
        help: one.help,
        nodes: one.nodes.map((node) => ({ target: node.target, html: node.html.slice(0, 160), why: (node.failureSummary ?? '').replace(/\s+/g, ' ').slice(0, 240) })),
      }))
    },
    within ?? null,
  )
  expect(violations, `${what}: axe`).toEqual([])
}
