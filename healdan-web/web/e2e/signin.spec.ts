// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { createRequire } from 'node:module'
import { expect, test, type Page } from '@playwright/test'
import { PASSWORD, signIn } from './helpers'

async function personMenu(page: Page) {
  await page.getByRole('banner').getByRole('button', { name: /web/i }).click()
}

test.beforeAll(() => {
  expect(PASSWORD, 'WEBUSER_PASSWORD is set by docker/tests/test-web.sh').not.toBe('')
})

test('a link into the app signs in, then comes back to it', async ({ page }) => {
  await page.goto('/app/recent/folders')
  await expect(page).toHaveURL(/\/login\.jsp/)
  await signIn(page)
  await expect(page).toHaveURL(/\/app\/recent\/folders$/)
  await expect(page.getByRole('heading', { name: 'Recent Folders' })).toBeVisible()
})

test('signing in from the sign-in page lands in the app', async ({ page }) => {
  await page.goto('/login.jsp')
  await signIn(page)
  await expect(page).toHaveURL(/\/app\/$/)
  await expect(page.getByRole('heading', { name: 'Recent Documents' })).toBeVisible()
})

test('the Classic Interface and Open in Healdan go across and back, signed in', async ({ page }) => {
  await page.goto('/login.jsp')
  await signIn(page)
  await expect(page).toHaveURL(/\/app\/$/)
  await personMenu(page)
  await page.getByRole('menuitem', { name: 'Classic Interface' }).click()
  await expect(page).toHaveURL(/\/frontend\.jsp/)
  const open = page.getByText('Open in Healdan')
  await expect(open).toBeVisible()
  await open.click()
  await expect(page).toHaveURL(/\/app\/$/)
  await expect(page.getByRole('heading', { name: 'Recent Documents' })).toBeVisible()
})

test('Sign Out lands on the sign-in page, and the app then asks for sign-in again', async ({ page }) => {
  await page.goto('/login.jsp')
  await signIn(page)
  await expect(page).toHaveURL(/\/app\/$/)
  await personMenu(page)
  await page.getByRole('menuitem', { name: 'Sign Out' }).click()
  await expect(page).toHaveURL(/\/login\.jsp/)
  await page.goto('/app/')
  await expect(page).toHaveURL(/\/login\.jsp/)
})

test.describe('accessibility', () => {
  test.use({ bypassCSP: true })

  test('the app has no accessibility violations a scan can find', async ({ page }) => {
    await page.goto('/login.jsp')
    await signIn(page)
    await expect(page.getByRole('heading', { name: 'Recent Documents' })).toBeVisible()
    const require = createRequire(import.meta.url)
    // The page's Content-Security-Policy refuses an injected script, so this test, and only this one, bypasses it.
    await page.addScriptTag({ path: require.resolve('axe-core/axe.min.js') })
    const found = await page.evaluate(async () => {
      // axe-core puts itself on window. The browser can check colour contrast, which the unit tests' jsdom can't.
      const axe = (window as unknown as { axe: { run: (c: Element, o: object) => Promise<{ violations: { id: string; nodes: { html: string }[] }[] }> } }).axe
      const result = await axe.run(document.documentElement, {})
      return result.violations.map((v) => `${v.id}: ${v.nodes.map((n) => n.html).join(' | ')}`)
    })
    expect(found).toEqual([])
  })
})
