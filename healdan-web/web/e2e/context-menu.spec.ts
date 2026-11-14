// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { expect, test, type APIRequestContext, type Locator, type Page } from '@playwright/test'
import { PASSWORD, authorized, call, deviceToken, escape, fileDocument, matterFolders, signIn, specsFolder } from './helpers'

// The right-click menus (changed after M6: the owner's dogfooding punch list, 2026-11-14): Rename from a document's menu
// opened by a right-click, Move To… from one opened by Shift+F10 on the focused row, and Remove from Favorites from a
// favorite's menu in the left pane. The documents and folders are this run's own, in a folder of its own inside the specs'
// E2E folder in matter 1.1's Documents folder (which the smoke fixture makes), so nothing depends on what an earlier step or
// run left (helpers' specsFolder).
const RUN = `menu${Date.now().toString(36)}`
const name = (what: string) => `${what} ${RUN}.txt`
const NAMES = { rename: name('Rename me'), move: name('Move me'), favorite: name('Favorite') }
const WORK = `Menus ${RUN}`
const MOVED = `Moved ${RUN}`

let api: APIRequestContext | undefined
let token = ''
let workFolder = 0
let movedFolder = 0
let matterFolder = 0
const numbers: Record<keyof typeof NAMES, number> = { rename: 0, move: 0, favorite: 0 }

const get = <T>(path: string) => call<T>(api!.get(`/api/v1${path}`, authorized(token)), `GET ${path}`)
const favoriteIds = async () => (await get<{ items: { id: number; item: { docNumber?: number } }[] }>('/me/favorites?limit=200')).items

test.beforeAll(async ({ playwright }, testInfo) => {
  expect(PASSWORD, 'WEBUSER_PASSWORD is set by docker/tests/test-web.sh').not.toBe('')
  api = await playwright.request.newContext({ baseURL: testInfo.project.use.baseURL })
  token = await deviceToken(api, RUN)
  const where = await matterFolders(api, token)
  matterFolder = where.matter
  const makeFolder = async (parentId: number, folderName: string) =>
    (await call<{ id: number }>(api!.post('/api/v1/folders', { ...authorized(token), data: { parentId, name: folderName } }), `making ${folderName}`)).id
  const home = await specsFolder(api, token, where.documents)
  workFolder = await makeFolder(home, WORK)
  movedFolder = await makeFolder(home, MOVED)
  for (const key of Object.keys(NAMES) as (keyof typeof NAMES)[])
    numbers[key] = await fileDocument(api, token, workFolder, NAMES[key], 'text/plain', Buffer.from(`${key} ${RUN}`))
  await call(api.post('/api/v1/me/favorites', { ...authorized(token), data: { docNumber: numbers.favorite } }), 'adding the favorite')
  // The move picker offers My Matters, where the matter is
  await call(api.put(`/api/v1/me/matters/${matterFolder}`, authorized(token)), 'adding the matter to My Matters')
})

test.afterAll(async () => {
  if (!api) return
  if (token) {
    // A run that failed before it removed its favorite leaves no bookmark behind
    for (const favorite of await favoriteIds().catch(() => [])) {
      if (favorite.item.docNumber === numbers.favorite) await api.delete(`/api/v1/me/favorites/${favorite.id}`, authorized(token))
    }
    await api.delete(`/api/v1/me/matters/${matterFolder}`, authorized(token))
    await api.delete('/api/v1/auth/tokens/current', authorized(token))
  }
  await api.dispose()
})

const main = (page: Page) => page.getByRole('main')
const row = (list: Locator, text: string) => list.getByRole('row', { name: escape(text) })
const document = (n: number) => call<{ name: string; folder: { id: number; name: string } }>(api!.get(`/api/v1/documents/${n}`, authorized(token)), `document ${n}`)

async function open(page: Page) {
  await page.goto('/login.jsp')
  await signIn(page)
  await expect(page.getByRole('heading', { name: 'Recent Documents' })).toBeVisible()
  await page.goto(`/app/folders/${workFolder}?sort=edited&order=desc`)
  const list = main(page).getByRole('grid')
  await expect(row(list, NAMES.favorite)).toBeVisible()
  return list
}

test('renames a document from the menu a right-click opens, which selects its row first', async ({ page }) => {
  const list = await open(page)
  await row(list, NAMES.rename).getByRole('rowheader').click({ button: 'right' })
  const menu = page.getByRole('menu', { name: `Actions for ${NAMES.rename}` })
  await expect(menu).toBeVisible()
  await expect(row(list, NAMES.rename)).toHaveAttribute('aria-selected', 'true')
  await expect(menu.getByRole('menuitem', { name: 'Open in New Tab' })).toHaveAttribute('href', `/app/doc/${numbers.rename}`)

  await menu.getByRole('menuitem', { name: 'Rename' }).click()
  const field = page.getByRole('textbox', { name: `New Name of ${NAMES.rename}` })
  await expect(field).toBeFocused()
  await field.fill(`Renamed ${RUN}`)
  await field.press('Enter')
  await expect(row(list, `Renamed ${RUN}.txt`)).toBeVisible()
  expect((await document(numbers.rename)).name).toBe(`Renamed ${RUN}.txt`)
})

test('moves a document from the menu Shift+F10 opens on its row, by keys', async ({ page }) => {
  const list = await open(page)
  // The row is selected and has the focus; the menu opens at it, its first item focused
  await row(list, NAMES.move).getByRole('rowheader').click()
  await expect(row(list, NAMES.move)).toHaveAttribute('aria-selected', 'true')
  await page.keyboard.press('Shift+F10')
  const menu = page.getByRole('menu', { name: `Actions for ${NAMES.move}` })
  await expect(menu.getByRole('menuitem', { name: 'Open', exact: true })).toBeFocused()
  // Esc closes it, and the focus is back in the row (on the cell that was clicked)
  await page.keyboard.press('Escape')
  await expect(menu).toHaveCount(0)
  await expect.poll(() => row(list, NAMES.move).evaluate((element) => element.contains(globalThis.document.activeElement))).toBe(true)

  await page.keyboard.press('Shift+F10')
  const item = menu.getByRole('menuitem', { name: 'Move To…' })
  for (let i = 0; i < 12 && !(await item.evaluate((element) => element === globalThis.document.activeElement)); i++) await page.keyboard.press('ArrowDown')
  await expect(item).toBeFocused()
  await page.keyboard.press('Enter')
  const picker = page.getByRole('dialog', { name: 'Move 1 Document' })
  await picker.getByRole('button', { name: /Smoke Matter/ }).first().click()
  await picker.getByRole('button', { name: /^Documents/ }).click()
  await picker.getByRole('button', { name: /^E2E/ }).click()
  await picker.getByRole('button', { name: escape(MOVED) }).click()
  await picker.getByRole('button', { name: 'Move Here' }).click()
  await expect(page.getByText(`Moved 1 document to ${MOVED}`)).toBeVisible()
  await expect(row(list, NAMES.move)).toHaveCount(0)
  expect((await document(numbers.move)).folder).toMatchObject({ id: movedFolder, name: MOVED })
})

test('removes a favorite from its menu in My Favorites', async ({ page }) => {
  await open(page)
  const favorites = page.getByRole('navigation', { name: 'Navigation' }).getByRole('region', { name: 'My Favorites' })
  const link = favorites.getByRole('link', { name: NAMES.favorite })
  await link.click({ button: 'right' })
  const menu = page.getByRole('menu', { name: `Actions for ${NAMES.favorite}` })
  await expect(menu.getByRole('menuitem')).toHaveText(['Open', 'Open in New Tab', 'Rename…', 'Remove from Favorites'])
  await menu.getByRole('menuitem', { name: 'Remove from Favorites' }).click()
  await expect(link).toHaveCount(0)
  expect((await favoriteIds()).some((favorite) => favorite.item.docNumber === numbers.favorite)).toBe(false)
})
