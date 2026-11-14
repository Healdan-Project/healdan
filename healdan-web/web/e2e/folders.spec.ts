// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { expect, test, type APIRequestContext, type Locator, type Page } from '@playwright/test'
import { PASSWORD, authorized, call, deviceToken, escape, matterFolders, signIn, specsFolder, tabTo } from './helpers'

// Renaming folders in the User app (folder rename spec): by F2 in the list, from the My Matters tree's ⋯ and from the
// open folder's heading, once by keyboard alone, with a taken name refused beside the field; and, for webuser, who isn't
// an administrator, Rename greyed out on matter 1.1's top-level Documents folder. The folders are this run's own, in a
// folder of its own inside the specs' E2E folder (helpers' specsFolder), so nothing earlier runs or smoke steps left
// is in their lists.
const RUN = `fld${Date.now().toString(36)}`
const NAMES = { home: `Folders ${RUN}`, byF2: `By F2 ${RUN}`, byTree: `By Tree ${RUN}`, byHeading: `By Heading ${RUN}`, byMenu: `By Menu ${RUN}`, keys: `Keys ${RUN}`, keyTarget: `Key Target ${RUN}`, taken: `Taken ${RUN}` }

let api: APIRequestContext | undefined
let token = ''
let matterFolder = 0
let documentsFolder = 0
const ids: Record<keyof typeof NAMES, number> = { home: 0, byF2: 0, byTree: 0, byHeading: 0, byMenu: 0, keys: 0, keyTarget: 0, taken: 0 }

test.beforeAll(async ({ playwright }, testInfo) => {
  expect(PASSWORD, 'WEBUSER_PASSWORD is set by docker/tests/test-web.sh').not.toBe('')
  api = await playwright.request.newContext({ baseURL: testInfo.project.use.baseURL })
  token = await deviceToken(api, RUN)
  const where = await matterFolders(api, token)
  matterFolder = where.matter
  documentsFolder = where.documents
  const makeFolder = async (parentId: number, name: string) =>
    (await call<{ id: number }>(api!.post('/api/v1/folders', { ...authorized(token), data: { parentId, name } }), `making ${name}`)).id
  ids.home = await makeFolder(await specsFolder(api, token, documentsFolder), NAMES.home)
  for (const key of ['byF2', 'byTree', 'byHeading', 'byMenu', 'keys', 'taken'] as const) ids[key] = await makeFolder(ids.home, NAMES[key])
  ids.keyTarget = await makeFolder(ids.keys, NAMES.keyTarget)
  // The tree is My Matters: it opens to the folder shown
  await call(api.put(`/api/v1/me/matters/${matterFolder}`, authorized(token)), 'adding the matter to My Matters')
})

test.afterAll(async () => {
  if (!api) return
  if (token) {
    await api.delete(`/api/v1/me/matters/${matterFolder}`, authorized(token))
    await api.delete('/api/v1/auth/tokens/current', authorized(token))
  }
  await api.dispose()
})

const main = (page: Page) => page.getByRole('main')
const tree = (page: Page) => page.getByRole('treegrid', { name: 'My Matters' })
const field = (page: Page, name: string) => page.getByRole('textbox', { name: `New Name of ${name}` })
const nameOf = async (id: number) => (await call<{ name: string }>(api!.get(`/api/v1/folders/${id}`, authorized(token)), `folder ${id}`)).name

async function open(page: Page, folderId: number) {
  await page.goto('/login.jsp')
  await signIn(page)
  await expect(page.getByRole('heading', { name: 'Recent Documents' })).toBeVisible()
  await page.goto(`/app/folders/${folderId}`)
}

test('F2 renames a folder row in place, and a taken name is refused beside the field', async ({ page }) => {
  await open(page, ids.home)
  const row: Locator = main(page).getByRole('grid').getByRole('row', { name: escape(NAMES.byF2) })
  // A click on a folder's row would open it
  await row.focus()
  await page.keyboard.press('F2')
  await field(page, NAMES.byF2).fill(NAMES.taken)
  await page.keyboard.press('Enter')
  await expect(page.getByRole('alert')).toHaveText(`There's already a folder called ${NAMES.taken} here.`)
  const renamed = `Renamed by F2 ${RUN}`
  await field(page, NAMES.byF2).fill(renamed)
  await page.keyboard.press('Enter')
  await expect(main(page).getByRole('grid').getByRole('row', { name: escape(renamed) })).toBeVisible()
  expect(await nameOf(ids.byF2)).toBe(renamed)
})

test("renames a folder from the tree's ⋯, and the heading and the breadcrumb follow", async ({ page }) => {
  await open(page, ids.byTree)
  const node = tree(page).getByRole('row', { name: escape(NAMES.byTree) })
  await node.hover()
  await node.getByRole('button', { name: `More for ${NAMES.byTree}` }).click()
  await page.getByRole('menuitem', { name: 'Rename' }).click()
  const renamed = `Renamed in the Tree ${RUN}`
  await field(page, NAMES.byTree).fill(renamed)
  await page.keyboard.press('Enter')
  await expect(tree(page).getByRole('row', { name: escape(renamed) })).toBeVisible()
  await expect(main(page).getByRole('heading', { level: 1, name: renamed })).toBeVisible()
  expect(await nameOf(ids.byTree)).toBe(renamed)
})

test("renames the open folder from the ⋯ beside its name", async ({ page }) => {
  await open(page, ids.byHeading)
  await main(page).getByRole('button', { name: `More for ${NAMES.byHeading}` }).click()
  await page.getByRole('menuitem', { name: 'Rename' }).click()
  const renamed = `Renamed by Its Heading ${RUN}`
  await field(page, NAMES.byHeading).fill(renamed)
  await page.keyboard.press('Enter')
  await expect(main(page).getByRole('heading', { level: 1, name: renamed })).toBeVisible()
  await expect(tree(page).getByRole('row', { name: escape(renamed) })).toBeVisible()
  expect(await nameOf(ids.byHeading)).toBe(renamed)
})

test('renames a folder from the menu a right-click opens', async ({ page }) => {
  await open(page, ids.home)
  const row = main(page).getByRole('grid').getByRole('row', { name: escape(NAMES.byMenu) })
  await row.getByRole('rowheader').click({ button: 'right' })
  await page.getByRole('menu', { name: `Actions for ${NAMES.byMenu}` }).getByRole('menuitem', { name: 'Rename' }).click()
  const renamed = `Renamed by Menu ${RUN}`
  await expect(field(page, NAMES.byMenu)).toBeFocused()
  await field(page, NAMES.byMenu).fill(renamed)
  await page.keyboard.press('Enter')
  await expect(main(page).getByRole('grid').getByRole('row', { name: escape(renamed) })).toBeVisible()
  expect(await nameOf(ids.byMenu)).toBe(renamed)
})

test('renames a folder by keyboard alone', async ({ page }) => {
  await open(page, ids.keys)
  const row = main(page).getByRole('grid').getByRole('row', { name: escape(NAMES.keyTarget) })
  await tabTo(page, row)
  await page.keyboard.press('F2')
  const renamed = `Renamed by Keys ${RUN}`
  await page.keyboard.press('ControlOrMeta+a')
  await page.keyboard.type(renamed)
  await page.keyboard.press('Enter')
  await expect(main(page).getByRole('grid').getByRole('row', { name: escape(renamed) })).toBeFocused()
  expect(await nameOf(ids.keyTarget)).toBe(renamed)
})

test("greys Rename out on a matter's top-level folder for someone who isn't an administrator", async ({ page }) => {
  await open(page, documentsFolder)
  await main(page).getByRole('button', { name: 'More for Documents' }).click()
  await expect(page.getByRole('menuitem', { name: 'Rename' })).toBeDisabled()
  await page.keyboard.press('Escape')
  const node = tree(page).getByRole('row', { name: /^Documents/ })
  await node.focus()
  await page.keyboard.press('F2')
  await expect(page.getByText("Only administrators can rename a matter's top-level folders.")).toBeVisible()
  await expect(page.getByRole('textbox')).toHaveCount(0)
})
