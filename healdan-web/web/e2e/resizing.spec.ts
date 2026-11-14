// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { expect, test, type APIRequestContext, type Locator, type Page } from '@playwright/test'
import { PASSWORD, authorized, call, deviceToken, escape, fileDocument, matterFolders, signIn, specsFolder } from './helpers'

// Resizing the panes (M6 spec section 6.1, changed after M6): the left pane's divider and the details' divider dragged by
// the mouse and moved by keys, the width kept across a reload, the list's columns following, and no dividers in a narrow
// window. Each test has a browser context of its own, so its storage starts empty, whatever another left. The document is
// this run's own, in a folder of its own inside the specs' E2E folder in matter 1.1's Documents folder (which the smoke
// fixture makes), so nothing depends on what an earlier step left.
const RUN = `rsz${Date.now().toString(36)}`
const NAME = `Resize me ${RUN}.txt`
const WORK = `Resize ${RUN}`

let api: APIRequestContext | undefined
let token = ''
let workFolder = 0
let docNumber = 0

test.beforeAll(async ({ playwright }, testInfo) => {
  expect(PASSWORD, 'WEBUSER_PASSWORD is set by docker/tests/test-web.sh').not.toBe('')
  api = await playwright.request.newContext({ baseURL: testInfo.project.use.baseURL })
  token = await deviceToken(api, RUN)
  const where = await matterFolders(api, token)
  const home = await specsFolder(api, token, where.documents)
  workFolder = (await call<{ id: number }>(api.post('/api/v1/folders', { ...authorized(token), data: { parentId: home, name: WORK } }), `making ${WORK}`)).id
  docNumber = await fileDocument(api, token, workFolder, NAME, 'text/plain', Buffer.from(`The text of ${RUN}`))
})

test.afterAll(async () => {
  if (!api) return
  if (token) await api.delete('/api/v1/auth/tokens/current', authorized(token))
  await api.dispose()
})

const main = (page: Page) => page.getByRole('main')
const width = async (locator: Locator) => (await locator.boundingBox())?.width ?? 0
/** How far a pane's drawn width is from the width its divider says: the right region's edge adds a pixel */
const off = async (locator: Locator, expected: number) => Math.abs((await width(locator)) - expected)
const valueOf = async (separator: Locator) => Number(await separator.getAttribute('aria-valuenow'))

async function open(page: Page, search = '') {
  await page.goto('/login.jsp')
  await signIn(page)
  await expect(page.getByRole('heading', { name: 'Recent Documents' })).toBeVisible()
  await page.goto(`/app/folders/${workFolder}${search}`)
  await expect(main(page).getByRole('grid').getByRole('row', { name: escape(NAME) })).toBeVisible()
}

/** Drags a divider sideways by the mouse, from its middle, in steps, as a person would */
async function drag(page: Page, separator: Locator, by: number) {
  const box = await separator.boundingBox()
  expect(box, 'the divider is drawn').toBeTruthy()
  const x = box!.x + box!.width / 2
  const y = box!.y + box!.height / 2
  await page.mouse.move(x, y)
  await page.mouse.down()
  await page.mouse.move(x + by, y, { steps: 8 })
  await page.mouse.up()
}

test('the left pane is resized by a drag and by keys, keeps its width across a reload, and has no divider in a narrow window', async ({ page }) => {
  await open(page)
  const pane = page.getByRole('navigation', { name: 'Navigation' })
  const separator = page.getByRole('separator', { name: 'Resize Navigation' })
  const title = main(page).getByRole('columnheader', { name: 'Title' })
  await expect(separator).toHaveAttribute('aria-valuenow', '248')
  expect(await off(pane, 248)).toBeLessThanOrEqual(1)
  const titleBefore = await width(title)

  await drag(page, separator, 80)
  await expect(separator).toHaveAttribute('aria-valuenow', '328')
  await expect.poll(() => off(pane, 328)).toBeLessThanOrEqual(1)
  // The list's columns follow: the virtualiser measured the narrower list once the drag ended
  await expect.poll(() => width(title)).toBeLessThan(titleBefore - 40)

  await page.reload()
  await expect(main(page).getByRole('grid').getByRole('row', { name: escape(NAME) })).toBeVisible()
  await expect(separator).toHaveAttribute('aria-valuenow', '328')
  expect(await off(pane, 328)).toBeLessThanOrEqual(1)

  // By keys: 16 pixels an arrow, and Enter back to the default
  await separator.focus()
  await page.keyboard.press('ArrowLeft')
  await expect(separator).toHaveAttribute('aria-valuenow', '312')
  await expect.poll(() => off(pane, 312)).toBeLessThanOrEqual(1)

  // Narrow, the pane folds away and there's no divider; wide again, the width kept comes back
  await page.setViewportSize({ width: 800, height: 900 })
  await expect(page.getByRole('button', { name: 'Navigation' })).toBeVisible()
  await expect(page.getByRole('separator')).toHaveCount(0)
  await page.setViewportSize({ width: 1280, height: 720 })
  await expect(separator).toHaveAttribute('aria-valuenow', '312')

  await separator.focus()
  await page.keyboard.press('Enter')
  await expect(separator).toHaveAttribute('aria-valuenow', '248')
  await page.reload()
  await expect(separator).toHaveAttribute('aria-valuenow', '248')
  expect(await off(pane, 248)).toBeLessThanOrEqual(1)
})

test('the details are resized from their edge, and keep their width across a reload', async ({ page }) => {
  await open(page, `?sel=${docNumber}`)
  const region = main(page).getByRole('region', { name: 'Details' })
  const separator = main(page).getByRole('separator', { name: 'Resize Details' })
  await expect(region).toBeVisible()
  await expect(separator).toHaveAttribute('aria-valuenow', '300')
  expect(await off(region, 300)).toBeLessThanOrEqual(1)

  // To the left widens them
  await drag(page, separator, -60)
  await expect(separator).toHaveAttribute('aria-valuenow', '360')
  await expect.poll(() => off(region, 360)).toBeLessThanOrEqual(1)

  await page.reload()
  await expect(region).toBeVisible()
  await expect(separator).toHaveAttribute('aria-valuenow', '360')
  expect(await off(region, 360)).toBeLessThanOrEqual(1)

  // End is as wide as the list leaves them, Home their narrowest, and Enter the default
  await separator.focus()
  await page.keyboard.press('End')
  const widest = await valueOf(separator)
  expect(widest).toBe(Number(await separator.getAttribute('aria-valuemax')))
  await expect.poll(() => off(region, widest)).toBeLessThanOrEqual(1)
  await page.keyboard.press('Home')
  await expect(separator).toHaveAttribute('aria-valuenow', '260')
  await page.keyboard.press('Enter')
  await expect(separator).toHaveAttribute('aria-valuenow', '300')
  await expect.poll(() => off(region, 300)).toBeLessThanOrEqual(1)
})
