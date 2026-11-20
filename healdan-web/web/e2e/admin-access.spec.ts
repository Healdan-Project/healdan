// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { expect, test, type Page } from '@playwright/test'
import { expectNoViolations } from './axe'
import { ADMIN_PASSWORD, ADMIN_USERNAME, PASSWORD, apiContext, signIn } from './helpers'

// The way into Healdan Admin (admin spec 6.1, 6.2, 6.4 and 17.1): a link to it followed signed out signs an administrator
// in and comes back to it; Admin in an administrator's person menu in the User app, and not in anyone else's; someone who
// isn't an administrator gets the no-access page and none of the app's files, and nor does someone signed out. The file is
// a real one of the build, named by the page the administrator got. Nothing here changes anything on the stack: it signs
// in as the stack's admin and as webuser, and only reads, so it depends on nothing an earlier step left.
test.describe.configure({ mode: 'serial' })

const ADMIN = { username: ADMIN_USERNAME, password: ADMIN_PASSWORD }

/** A file of the Admin app's build, as the page an administrator got names it, and its bytes as they got them */
let asset = ''
let assetBytes = Buffer.alloc(0)

const dashboard = (page: Page) => page.getByRole('main').getByRole('heading', { level: 1, name: 'Dashboard' })

/** Opens the person menu, the top bar's last menu button in either app, and waits for it */
async function personMenu(page: Page) {
  await page.getByRole('banner').locator('button[aria-haspopup]').last().click()
  await expect(page.getByRole('menuitem', { name: 'Sign Out' })).toBeVisible()
}

/** Signs in from the sign-in page, then opens the User app, whatever the sign-in lands on */
async function intoTheUserApp(page: Page, who?: { username: string; password: string }) {
  await page.goto('/login.jsp')
  await signIn(page, who)
  await expect(page).not.toHaveURL(/\/login\.jsp/)
  await page.goto('/app/')
  await expect(page.getByRole('heading', { name: 'Recent Documents' })).toBeVisible()
}

/** Whether the answer holds the file's bytes: its first 256, which no page of the User app holds */
const holdsTheFile = (body: Buffer) => body.includes(assetBytes.subarray(0, 256))

test.beforeAll(() => {
  expect(PASSWORD, 'WEBUSER_PASSWORD is set by docker/tests/test-web.sh').not.toBe('')
  expect(ADMIN_PASSWORD, 'ADMIN_PASSWORD is set by docker/tests/test-web.sh').not.toBe('')
})

test('a link into Healdan Admin, followed signed out, signs an administrator in and comes back to it', async ({ page }) => {
  // An address with a query the app doesn't know, which the sign-in keeps and the app ignores
  await page.goto('/admin/?x=1')
  await expect(page).toHaveURL(/\/login\.jsp/)
  await signIn(page, ADMIN)
  await expect(page).toHaveURL(/\/admin\/\?x=1$/)
  await expect(dashboard(page)).toBeVisible()
  for (const part of ['Needs Attention', 'Server Health', 'Usage']) await expect(page.getByRole('main').getByRole('heading', { level: 2, name: part })).toBeVisible()

  // The build's entry script, which the page names, as the administrator gets it: whole, and kept by no proxy for anyone else
  asset = (await page.locator('script[type="module"][src^="/admin/assets/"]').getAttribute('src')) ?? ''
  expect(asset, 'the page names its script under /admin/assets/').toMatch(/^\/admin\/assets\/[^/]+\.js$/)
  const answer = await page.request.get(asset)
  expect(answer.status()).toBe(200)
  expect(answer.headers()['cache-control']).toMatch(/^private,/)
  assetBytes = await answer.body()
  expect(assetBytes.length, 'the script has more bytes than the check reads').toBeGreaterThan(256)
})

test("an administrator has Admin in the User app's person menu, before Classic Interface, and it opens the Dashboard", async ({ page }) => {
  await intoTheUserApp(page, ADMIN)
  await personMenu(page)
  const items = await page.getByRole('menuitem').allTextContents()
  expect(items.indexOf('Admin'), `Admin before Classic Interface in ${items.join(', ')}`).toBe(items.indexOf('Classic Interface') - 1)
  await page.getByRole('menuitem', { name: 'Admin', exact: true }).click()
  await expect(page).toHaveURL(/\/admin\/$/)
  await expect(dashboard(page)).toBeVisible()
})

test("someone who isn't an administrator has no Admin, gets the no-access page with the way to the User app, and none of the app's files", async ({ page }) => {
  await intoTheUserApp(page)
  await personMenu(page)
  await expect(page.getByRole('menuitem', { name: 'Admin', exact: true })).toHaveCount(0)
  await page.keyboard.press('Escape')

  const answer = await page.goto('/admin/')
  expect(answer?.status()).toBe(403)
  await expect(page.getByRole('heading', { level: 1, name: "You don't have access to Healdan Admin." })).toBeVisible()
  const open = page.getByRole('link', { name: 'Open Healdan' })
  await expect(open).toHaveAttribute('href', '/app/')
  await expectNoViolations(page, 'the no-access page')

  // The real file, asked for with webuser's own session: refused, with the User app's page and not a byte of the file
  expect(asset, 'the first test read a file of the build').not.toBe('')
  const refused = await page.request.get(asset)
  expect(refused.status()).toBe(403)
  expect(refused.headers()['cache-control']).toBe('no-store')
  expect(holdsTheFile(await refused.body()), 'the refusal holds the file').toBe(false)

  await open.click()
  await expect(page).toHaveURL(/\/app\/$/)
  await expect(page.getByRole('heading', { name: 'Recent Documents' })).toBeVisible()
})

test('signed out, a file of the build is refused', async ({ playwright }, testInfo) => {
  expect(asset, 'the first test read a file of the build').not.toBe('')
  // A context of its own, with no session
  const api = await apiContext(playwright.request, testInfo.project.use.baseURL)
  try {
    const answer = await api.get(asset)
    expect(answer.status()).not.toBe(200)
    expect(holdsTheFile(await answer.body()), 'the refusal holds the file').toBe(false)
  } finally {
    await api.dispose()
  }
})
