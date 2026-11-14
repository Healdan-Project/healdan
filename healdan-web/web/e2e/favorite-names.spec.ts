// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { expect, test, type APIRequestContext } from '@playwright/test'
import { PASSWORD, authorized, call, deviceToken, escape, escaped, matterFolders, signIn, specsFolder } from './helpers'

// A favorite's own name (changed after M6: the owner's dogfooding punch list, 2026-11-14): a folder added to My Favorites
// with its star, renamed from its menu in My Favorites, and given its own name back with Use Original Name. The folder is
// this run's own, inside the specs' E2E folder in matter 1.1's Documents folder (which the smoke fixture makes), so nothing
// depends on what an earlier step or run left (helpers' specsFolder).
const RUN = `favname${Date.now().toString(36)}`
const FOLDER = `Favorite Names ${RUN}`
const OWN = `Smoke Discovery Docs ${RUN}`

let api: APIRequestContext | undefined
let token = ''
let folderId = 0

interface Favorite {
  id: number
  name: string
  item: { id?: number; name: string }
}

const favorites = async () => (await call<{ items: Favorite[] }>(api!.get('/api/v1/me/favorites', authorized(token)), 'listing the favorites')).items
const ours = async () => (await favorites()).find((favorite) => favorite.item.id === folderId)

test.beforeAll(async ({ playwright }, testInfo) => {
  expect(PASSWORD, 'WEBUSER_PASSWORD is set by docker/tests/test-web.sh').not.toBe('')
  api = await playwright.request.newContext({ baseURL: testInfo.project.use.baseURL })
  token = await deviceToken(api, RUN)
  const home = await specsFolder(api, token, (await matterFolders(api, token)).documents)
  folderId = (await call<{ id: number }>(api.post('/api/v1/folders', { ...authorized(token), data: { parentId: home, name: FOLDER } }), `making ${FOLDER}`)).id
})

test.afterAll(async () => {
  if (!api) return
  if (token) {
    // A run that failed before the end leaves no bookmark behind
    const favorite = await ours().catch(() => undefined)
    if (favorite) await api.delete(`/api/v1/me/favorites/${favorite.id}`, authorized(token))
    await api.delete('/api/v1/auth/tokens/current', authorized(token))
  }
  await api.dispose()
})

test('a favorite folder renamed from its menu shows its own name, and Use Original Name puts back the folder’s', async ({ page }) => {
  await page.goto('/login.jsp')
  await signIn(page)
  await expect(page.getByRole('heading', { name: 'Recent Documents' })).toBeVisible()
  await page.goto(`/app/folders/${folderId}`)
  const main = page.getByRole('main')
  await expect(main.getByRole('heading', { name: escape(FOLDER) })).toBeVisible()
  await main.getByRole('button', { name: 'Add to Favorites' }).click()

  const mine = page.getByRole('navigation', { name: 'Navigation' }).getByRole('region', { name: 'My Favorites' })
  const original = mine.getByRole('link', { name: FOLDER, exact: true })
  await expect(original).toBeVisible()
  // The tooltip says what it really is and where
  await expect(original).toHaveAttribute('title', new RegExp(`^${escaped(FOLDER)} · 1\\.1 – `))

  await original.click({ button: 'right' })
  const menu = page.getByRole('menu', { name: `Actions for ${FOLDER}` })
  await expect(menu.getByRole('menuitem')).toHaveText(['Open', 'Open in New Tab', 'Rename…', 'Remove from Favorites'])
  await menu.getByRole('menuitem', { name: 'Rename…' }).click()
  const field = page.getByRole('textbox', { name: `Name of Favorite ${FOLDER}` })
  await expect(field).toBeFocused()
  await field.fill(OWN)
  await field.press('Enter')

  const renamed = mine.getByRole('link', { name: OWN, exact: true })
  await expect(renamed).toBeVisible()
  await expect(renamed).toBeFocused()
  await expect(renamed).toHaveAttribute('title', new RegExp(`^${escaped(FOLDER)} · 1\\.1 – `))
  expect(await ours()).toMatchObject({ name: OWN, item: { name: FOLDER } })
  // The folder keeps its own name
  await expect(main.getByRole('heading', { name: escape(FOLDER) })).toBeVisible()

  await renamed.click({ button: 'right' })
  const ownMenu = page.getByRole('menu', { name: `Actions for ${OWN}` })
  await ownMenu.getByRole('menuitem', { name: 'Use Original Name' }).click()
  await expect(mine.getByRole('link', { name: FOLDER, exact: true })).toBeVisible()
  expect(await ours()).toMatchObject({ name: FOLDER })
})
