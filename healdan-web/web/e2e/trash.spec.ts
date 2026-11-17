// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { expect, test, type APIRequestContext, type Locator, type Page } from '@playwright/test'
import { ADMIN_PASSWORD, ADMIN_USERNAME, PASSWORD, apiContext, authorized, call, deviceToken, escape, escaped, fileDocument, matterFolders, signIn, specsFolder } from './helpers'

// The Trash in the User app (folders and Trash spec 7): a folder deleted from the menu a right-click opens, with Undo on the
// toast; a folder of its own deleted and restored from the Trash, where the toast's Open goes to it; one removed, which an
// administrator's Removed view then lists; and two documents deleted and the Trash emptied. The folders and documents are this
// run's own, in a folder of their own inside the specs' E2E folder in matter 1.1's Documents folder (which the smoke fixture
// makes), and each test makes the delete it checks, so none depends on another or on what an earlier run left in the Trash.
const RUN = `trs${Date.now().toString(36)}`
const HOME = `Trash ${RUN}`
const UNDONE = `Undone ${RUN}`
const RESTORED = `Restored ${RUN}`
const REMOVED = `Removed ${RUN}`
const name = (what: string) => `${what} ${RUN}.txt`
const DOCUMENTS = { undone: name('In undone'), restored: name('In restored'), removed: name('In removed'), emptyOne: name('Empty one'), emptyTwo: name('Empty two') }

let api: APIRequestContext | undefined
let token = ''
let adminToken = ''
let home = 0
const folders = { undone: 0, restored: 0, removed: 0 }
const numbers: Record<keyof typeof DOCUMENTS, number> = { undone: 0, restored: 0, removed: 0, emptyOne: 0, emptyTwo: 0 }

const get = (path: string, as = token) => api!.get(`/api/v1${path}`, authorized(as))
const status = async (path: string) => (await get(path)).status()

test.beforeAll(async ({ playwright }, testInfo) => {
  expect(PASSWORD, 'WEBUSER_PASSWORD is set by docker/tests/test-web.sh').not.toBe('')
  api = await apiContext(playwright.request, testInfo.project.use.baseURL)
  token = await deviceToken(api, RUN)
  const where = await matterFolders(api, token)
  const makeFolder = async (parentId: number, folderName: string) =>
    (await call<{ id: number }>(api!.post('/api/v1/folders', { ...authorized(token), data: { parentId, name: folderName } }), `making ${folderName}`)).id
  home = await makeFolder(await specsFolder(api, token, where.documents), HOME)
  folders.undone = await makeFolder(home, UNDONE)
  folders.restored = await makeFolder(home, RESTORED)
  folders.removed = await makeFolder(home, REMOVED)
  numbers.undone = await fileDocument(api, token, folders.undone, DOCUMENTS.undone, 'text/plain', Buffer.from(`undone ${RUN}`))
  numbers.restored = await fileDocument(api, token, folders.restored, DOCUMENTS.restored, 'text/plain', Buffer.from(`restored ${RUN}`))
  numbers.removed = await fileDocument(api, token, folders.removed, DOCUMENTS.removed, 'text/plain', Buffer.from(`removed ${RUN}`))
  // The two the last test deletes sit in the home folder itself, where nothing else is a document
  numbers.emptyOne = await fileDocument(api, token, home, DOCUMENTS.emptyOne, 'text/plain', Buffer.from(`one ${RUN}`))
  numbers.emptyTwo = await fileDocument(api, token, home, DOCUMENTS.emptyTwo, 'text/plain', Buffer.from(`two ${RUN}`))
})

test.afterAll(async () => {
  if (!api) return
  if (adminToken) await api.delete('/api/v1/auth/tokens/current', authorized(adminToken))
  if (token) await api.delete('/api/v1/auth/tokens/current', authorized(token))
  await api.dispose()
})

const main = (page: Page) => page.getByRole('main')
const left = (page: Page) => page.getByRole('navigation', { name: 'Navigation' })
const row = (list: Locator, text: string) => list.getByRole('row', { name: escape(text) })

async function open(page: Page, folderId: number) {
  await page.goto('/login.jsp')
  await signIn(page)
  await expect(page.getByRole('heading', { name: 'Recent Documents' })).toBeVisible()
  await page.goto(`/app/folders/${folderId}?sort=title&order=asc`)
}

/** Deletes a folder from the menu a right-click on its row opens, and confirms the question, which says what it holds */
async function deleteFromMenu(page: Page, folderName: string, holds: RegExp) {
  const list = main(page).getByRole('grid')
  await row(list, folderName).getByRole('rowheader').click({ button: 'right' })
  await page.getByRole('menu', { name: `Actions for ${folderName}` }).getByRole('menuitem', { name: 'Delete' }).click()
  const question = page.getByRole('dialog', { name: `Move "${folderName}" to the Trash?` })
  await expect(question.getByText(holds)).toBeVisible()
  await question.getByRole('button', { name: 'Move to Trash' }).click()
  await expect(page.getByText(`Moved "${folderName}" to the Trash`)).toBeVisible()
  await expect(row(list, folderName)).toHaveCount(0)
}

const trashRow = (page: Page, itemName: string) => main(page).getByRole('grid').getByRole('row', { name: escape(itemName) })

async function openTrash(page: Page) {
  await left(page).getByRole('link', { name: 'Trash', exact: true }).click()
  await expect(page).toHaveURL(/\/app\/trash/)
  await expect(main(page).getByRole('heading', { level: 1, name: 'Trash' })).toBeVisible()
}

test('deletes a folder from its right-click menu, and Undo on the toast puts it and its document back', async ({ page }) => {
  await open(page, home)
  await deleteFromMenu(page, UNDONE, /1 document/)
  expect(await status(`/folders/${folders.undone}`)).toBe(404)
  expect(await status(`/documents/${numbers.undone}`)).toBe(404)

  await page.getByRole('button', { name: 'Undo' }).click()
  await expect(page.getByText(`Restored "${UNDONE}"`)).toBeVisible()
  await expect(row(main(page).getByRole('grid'), UNDONE)).toBeVisible()
  expect(await status(`/folders/${folders.undone}`)).toBe(200)
  expect(await status(`/documents/${numbers.undone}`)).toBe(200)
  expect(((await (await get(`/documents/${numbers.undone}`)).json()) as { folder: { id: number } }).folder.id).toBe(folders.undone)
})

test("deletes a folder of its own, and Restore in the Trash puts it back where it was deleted from, which the toast's Open goes to", async ({ page }) => {
  await open(page, home)
  await deleteFromMenu(page, RESTORED, /1 document/)

  await openTrash(page)
  const item = trashRow(page, RESTORED)
  await expect(item).toBeVisible()
  // What a delete is called in the Trash: one row for the folder, with how much it holds, and where it was deleted from
  await expect(item).toContainText('1 item')
  await expect(item).toContainText(HOME)
  await item.getByRole('rowheader').click()
  await expect(page.getByRole('region', { name: 'Details' })).toBeVisible()
  await main(page).getByRole('toolbar', { name: 'Trash Actions' }).getByRole('button', { name: 'Restore', exact: true }).click()
  await expect(page.getByText(new RegExp(`^Restored "${escaped(RESTORED)}" to `))).toBeVisible()
  await expect(item).toHaveCount(0)

  await page.getByRole('button', { name: 'Open', exact: true }).click()
  await expect(page).toHaveURL(new RegExp(`/app/folders/${home}$`))
  await expect(row(main(page).getByRole('grid'), RESTORED)).toBeVisible()
  const back = (await (await get(`/folders/${folders.restored}`)).json()) as { path: { id: number }[] }
  expect(back.path.at(-2)?.id).toBe(home)
  expect(await status(`/documents/${numbers.restored}`)).toBe(200)
})

test("removes a folder from the Trash after the question, and an administrator's Removed view lists it", async ({ page }) => {
  await open(page, home)
  await deleteFromMenu(page, REMOVED, /1 document/)

  await openTrash(page)
  const item = trashRow(page, REMOVED)
  await item.getByRole('rowheader').click()
  await main(page).getByRole('toolbar', { name: 'Trash Actions' }).getByRole('button', { name: 'Remove…' }).click()
  const question = page.getByRole('dialog', { name: `Remove "${REMOVED}" from the Trash?` })
  await expect(question.getByText(/You won't be able to restore it\. An administrator can, for \d+ days\./)).toBeVisible()
  await question.getByRole('button', { name: 'Remove', exact: true }).click()
  await expect(item).toHaveCount(0)

  // It's out of the person's Trash, and the folder and its document stay gone
  const mine = (await (await get('/trash?limit=200')).json()) as { items: { name: string }[] }
  expect(mine.items.map((one) => one.name)).not.toContain(REMOVED)
  expect(await status(`/folders/${folders.removed}`)).toBe(404)
  expect(await status(`/documents/${numbers.removed}`)).toBe(404)

  // An administrator's token: the Removed view lists it, with who removed it and the night it is destroyed
  expect(ADMIN_PASSWORD, 'ADMIN_PASSWORD is set by docker/tests/test-web.sh').not.toBe('')
  adminToken = await deviceToken(api!, RUN, { username: ADMIN_USERNAME, password: ADMIN_PASSWORD })
  const removed = await call<{ items: { name: string; removedBy: { name: string } | null; removedAt: string | null; destroyAfter: string | null }[] }>(
    get('/trash?view=removed&limit=200', adminToken),
    "an administrator's Removed view",
  )
  const listed = removed.items.find((one) => one.name === REMOVED)
  expect(listed, 'the Removed view lists the item').toBeTruthy()
  expect(listed!.removedAt).not.toBeNull()
  expect(listed!.destroyAfter).not.toBeNull()
  // Someone who isn't an administrator is refused the view
  expect((await get('/trash?view=removed')).status()).toBe(403)
})

test('deletes two documents and Empty Trash leaves the Trash empty', async ({ page }) => {
  // Two documents deleted through the API, as the person would from the list (organising.spec asks the question): the Trash holds them
  await call(api!.delete(`/api/v1/documents/${numbers.emptyOne}`, authorized(token)), 'deleting one document')
  await call(api!.delete(`/api/v1/documents/${numbers.emptyTwo}`, authorized(token)), 'deleting the other')
  await open(page, home)
  await openTrash(page)
  await expect(trashRow(page, DOCUMENTS.emptyOne)).toBeVisible()
  await expect(trashRow(page, DOCUMENTS.emptyTwo)).toBeVisible()

  await main(page).getByRole('toolbar', { name: 'Trash Actions' }).getByRole('button', { name: 'Empty Trash…' }).click()
  const question = page.getByRole('dialog', { name: 'Empty the Trash?' })
  await expect(question.getByText(/^This removes all \d+ items you deleted\.$/)).toBeVisible()
  await expect(question.getByText(/You won't be able to restore it\. An administrator can, for \d+ days\./)).toBeVisible()
  // Cancel, which has the focus, removes nothing
  await expect(question.getByRole('button', { name: 'Cancel' })).toBeFocused()
  await question.getByRole('button', { name: 'Cancel' }).click()
  await expect(trashRow(page, DOCUMENTS.emptyOne)).toBeVisible()

  await main(page).getByRole('toolbar', { name: 'Trash Actions' }).getByRole('button', { name: 'Empty Trash…' }).click()
  await page.getByRole('dialog', { name: 'Empty the Trash?' }).getByRole('button', { name: 'Empty Trash', exact: true }).click()
  await expect(main(page).getByText('Nothing in the Trash.')).toBeVisible()
  const mine = (await (await get('/trash')).json()) as { items: unknown[]; total?: number }
  expect(mine.items).toEqual([])
  expect(await status(`/documents/${numbers.emptyOne}`)).toBe(404)
})
