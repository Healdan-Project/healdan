// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { expect, test, type APIRequestContext, type Page } from '@playwright/test'
import { PASSWORD, authorized, deviceToken, fileDocument, matterFolders, signIn } from './helpers'

// Finding in the User app (M6 spec section 6.3): the search box and its scopes, a matter opened by its number, My Matters
// and its tree, a document found by its words and by its #, Filters, and Copy Link. The documents are made here, through
// the API with a device token, in matter 1.1's Documents folder (which the smoke fixture makes), with names that are
// this run's own, so nothing depends on what an earlier step left. Both browser projects run this file against one
// stack, each with its own run.
const RUN = `fnd${Date.now().toString(36)}`
const NAMES = { alpha: `Charterparty ${RUN} Alpha.txt`, beta: `Charterparty ${RUN} Beta.txt`, scan: `Charterparty ${RUN} Scan.png` }
// One pixel, for the Picture among the documents
const PNG = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==', 'base64')

let api: APIRequestContext | undefined
let token = ''
let matterFolder = 0
let alpha = 0

const file = (folderId: number, name: string, mimeType: string, buffer: Buffer) => fileDocument(api!, token, folderId, name, mimeType, buffer)

test.beforeAll(async ({ playwright }, testInfo) => {
  expect(PASSWORD, 'WEBUSER_PASSWORD is set by docker/tests/test-web.sh').not.toBe('')
  api = await playwright.request.newContext({ baseURL: testInfo.project.use.baseURL })
  token = await deviceToken(api, RUN)
  const folders = await matterFolders(api, token)
  matterFolder = folders.matter
  const documents = { id: folders.documents }
  // The contents don't hold the run's word, so a hit is the title's: the search looks in both
  alpha = await file(documents.id, NAMES.alpha, 'text/plain', Buffer.from('Quarterly note, first'))
  await file(documents.id, NAMES.beta, 'text/plain', Buffer.from('Quarterly note, second'))
  await file(documents.id, NAMES.scan, 'image/png', PNG)
  // The matter starts out of My Matters, whatever the other browser's run left
  await api.delete(`/api/v1/me/matters/${matterFolder}`, authorized(token))
})

test.afterAll(async () => {
  // beforeAll may have failed before there was anything to clean up
  if (!api) return
  if (token) {
    if (matterFolder) await api.delete(`/api/v1/me/matters/${matterFolder}`, authorized(token))
    await api.delete('/api/v1/auth/tokens/current', authorized(token))
  }
  await api.dispose()
})

const box = (page: Page) => page.getByRole('combobox', { name: 'Search' })
const main = (page: Page) => page.getByRole('main')

async function start(page: Page) {
  await page.goto('/login.jsp')
  await signIn(page)
  await expect(page.getByRole('heading', { name: 'Recent Documents' })).toBeVisible()
}

// Search is full text, which the index fills a moment after a document is filed: the search is asked again until the
// document shows.
async function searchUntilFound(page: Page, words: string, name: string) {
  const row = main(page).getByRole('row', { name: new RegExp(name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')) })
  await expect(async () => {
    await page.goto(`/app/search?scope=documents&q=${encodeURIComponent(words)}`)
    await expect(row).toBeVisible({ timeout: 4_000 })
  }).toPass({ timeout: 80_000, intervals: [1_000, 2_000, 3_000] })
  return row
}

test('opens matter 1.1 through the Matters scope, adds it to My Matters and opens it from the tree', async ({ page }) => {
  await start(page)
  await page.getByRole('button', { name: /Search In/ }).click()
  await page.getByRole('menuitem', { name: 'Matters' }).click()
  await expect(page.getByRole('button', { name: /Search In.*Matters/ })).toBeVisible()
  await box(page).fill('1.1')
  await box(page).press('Enter')
  await expect(page).toHaveURL(new RegExp(`/app/folders/${matterFolder}$`))
  await expect(main(page).getByRole('heading', { level: 1 })).toContainText('Smoke Matter')

  const nav = page.getByRole('navigation', { name: 'Navigation' })
  await page.getByRole('button', { name: 'Add This Matter to My Matters' }).click()
  const tree = nav.getByRole('treegrid', { name: 'My Matters' })
  await expect(tree.getByRole('row', { name: /Smoke Matter/ })).toBeVisible()

  // Away, and back by the tree
  await page.getByRole('link', { name: 'Healdan' }).click()
  await expect(page).toHaveURL(/\/app\/$/)
  await tree.getByRole('row', { name: /Smoke Matter/ }).click()
  await expect(page).toHaveURL(new RegExp(`/app/folders/${matterFolder}$`))
  await expect(main(page).getByRole('heading', { level: 1 })).toContainText('Smoke Matter')
})

test('finds a document by its title, and by its #', async ({ page }) => {
  await start(page)
  await box(page).fill(RUN)
  await box(page).press('Enter')
  await expect(page).toHaveURL(/\/app\/search\?scope=documents&q=/)
  await expect(main(page).getByRole('heading', { level: 1, name: `"${RUN}"` })).toBeVisible()
  // The suggestions and the first search may come before the index has the documents
  await searchUntilFound(page, RUN, NAMES.alpha)
  await expect(main(page).getByRole('row', { name: new RegExp(NAMES.beta.replace('.', '\\.')) })).toBeVisible()
  // The Matter column says where each is
  await expect(main(page).getByRole('row', { name: new RegExp(NAMES.alpha.replace('.', '\\.')) })).toContainText('Smoke Matter')

  await box(page).fill(`#${alpha}`)
  await box(page).press('Enter')
  await expect(page).toHaveURL(new RegExp(`/app/doc/${alpha}$`))
  await expect(main(page).getByRole('heading', { level: 1, name: `Document # ${alpha}` })).toBeVisible()
  const details = page.getByRole('region', { name: 'Details' })
  await expect(details.getByRole('heading', { name: NAMES.alpha })).toBeVisible()
  await expect(details.getByText('Location')).toBeVisible()
})

test('filters the results by file type', async ({ page }) => {
  // The search's wait and the rounds below together can pass the default 90 seconds when the server answers empty a few times
  test.setTimeout(170_000)
  await start(page)
  await searchUntilFound(page, RUN, NAMES.scan)
  // The Indexer runs every 5 seconds in this step, and while it does the server now and then answers a search, or the count
  // of its filters' options, with nothing at all, the same one a moment later having it all (seen by asking the API directly,
  // with and without a filter). The page asks once and keeps the answer, so each round starts the search over, from its
  // address, until one round gets through; what a round checks is what a person would do and see.
  const results = main(page)
  await expect(async () => {
    await page.goto(`/app/search?scope=documents&q=${encodeURIComponent(RUN)}`)
    await page.getByRole('button', { name: /^Filters/ }).click()
    const filters = page.getByRole('region', { name: 'Filters' })
    // The checkbox itself is hidden under its label, as a person would click the words
    await filters.getByText(/^Picture/).click({ timeout: 5_000 })
    await expect(filters.getByRole('checkbox', { name: /^Picture/ })).toBeChecked({ timeout: 5_000 })
    await expect(page).toHaveURL(/type=picture/, { timeout: 5_000 })
    await expect(results.getByRole('row', { name: new RegExp(NAMES.scan.replace('.', '\\.')) })).toBeVisible({ timeout: 5_000 })
    await expect(results.getByRole('row', { name: new RegExp(NAMES.alpha.replace('.', '\\.')) })).toHaveCount(0)
    await expect(results.getByRole('group', { name: 'Filters in Use' })).toContainText('Picture')
    await expect(results.getByText('1 document', { exact: true })).toBeVisible({ timeout: 5_000 })
  }).toPass({ timeout: 70_000, intervals: [500] })
})

test('copies a document link and follows it', async ({ page, context, browserName }) => {
  // Chromium lets a test read the real clipboard once it's allowed. WebKit has no such permission, so, as the page
  // writes through navigator.clipboard.writeText, what it writes is also kept on window, and the link is read from there.
  if (browserName === 'chromium') await context.grantPermissions(['clipboard-read', 'clipboard-write'])
  await page.addInitScript(() => {
    const clipboard = navigator.clipboard
    const write = clipboard.writeText.bind(clipboard)
    clipboard.writeText = async (text: string) => {
      ;(window as unknown as { copied?: string }).copied = text
      await write(text).catch(() => {})
    }
  })
  await start(page)
  await searchUntilFound(page, RUN, NAMES.alpha)
  await main(page).getByRole('row', { name: new RegExp(NAMES.alpha.replace('.', '\\.')) }).getByRole('rowheader').click()
  const details = page.getByRole('region', { name: 'Details' })
  await expect(details.getByRole('heading', { name: NAMES.alpha })).toBeVisible()
  await details.getByRole('button', { name: 'Copy Link' }).click()
  await expect(page.getByText('Link copied')).toBeVisible()

  const link = await page.evaluate(() => (window as unknown as { copied?: string }).copied)
  expect(link).toMatch(new RegExp(`/app/doc/${alpha}$`))
  if (browserName === 'chromium') expect(await page.evaluate(() => navigator.clipboard.readText())).toBe(link)

  await page.goto(link!)
  await expect(page).toHaveURL(new RegExp(`/app/doc/${alpha}$`))
  await expect(main(page).getByRole('heading', { level: 1, name: `Document # ${alpha}` })).toBeVisible()
  await expect(page.getByRole('region', { name: 'Details' }).getByRole('heading', { name: NAMES.alpha })).toBeVisible()
})
