// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { expect, test, type APIRequestContext, type Locator, type Page } from '@playwright/test'
import { PASSWORD, authorized, call, deviceToken, fileDocument, matterFolders, signIn } from './helpers'

// Organising in the User app (M6 spec section 6.7): a folder made with New Folder, a document renamed in place, one moved
// by the picker, two moved by dragging them onto a folder of the tree, and two deleted after the question. Playwright can't
// drag between elements with a custom type, so the drag is the events a browser makes (dragstart on the row, then dragenter,
// dragover and drop on the folder) with one DataTransfer built in the page, which is what the row's dragstart fills. The
// documents are this run's own, in a folder of its own inside matter 1.1's Documents folder (which the smoke fixture makes), so
// the list they are in holds nothing else, whatever earlier runs left; the folders they are moved to are in Documents.
const RUN = `org${Date.now().toString(36)}`
const name = (what: string) => `${what} ${RUN}.txt`
const NAMES = { rename: name('Rename me'), pick: name('Pick me'), dragOne: name('Drag one'), dragTwo: name('Drag two'), deleteOne: name('Delete one'), deleteTwo: name('Delete two') }
const WORK = `Work ${RUN}`
const MADE = `Made ${RUN}`
const PICKED = `Picked ${RUN}`
const DRAGGED = `Dragged ${RUN}`

let api: APIRequestContext | undefined
let token = ''
let documentsFolder = 0
let workFolder = 0
let matterFolder = 0
const numbers: Record<keyof typeof NAMES, number> = { rename: 0, pick: 0, dragOne: 0, dragTwo: 0, deleteOne: 0, deleteTwo: 0 }
const folders: Record<'picked' | 'dragged', number> = { picked: 0, dragged: 0 }

test.beforeAll(async ({ playwright }, testInfo) => {
  expect(PASSWORD, 'WEBUSER_PASSWORD is set by docker/tests/test-web.sh').not.toBe('')
  api = await playwright.request.newContext({ baseURL: testInfo.project.use.baseURL })
  token = await deviceToken(api, RUN)
  const where = await matterFolders(api, token)
  documentsFolder = where.documents
  matterFolder = where.matter
  const makeFolder = async (parentId: number, folderName: string) =>
    (await call<{ id: number }>(api!.post('/api/v1/folders', { ...authorized(token), data: { parentId, name: folderName } }), `making ${folderName}`)).id
  workFolder = await makeFolder(documentsFolder, WORK)
  // Filed one after another, so a list newest first has each pair side by side
  for (const key of Object.keys(NAMES) as (keyof typeof NAMES)[])
    numbers[key] = await fileDocument(api, token, workFolder, NAMES[key], 'text/plain', Buffer.from(`${key} ${RUN}`))
  folders.picked = await makeFolder(documentsFolder, PICKED)
  folders.dragged = await makeFolder(documentsFolder, DRAGGED)
  // The tree is My Matters: it opens to the folder shown, where the drag's target is
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
const folderPath = () => `/app/folders/${workFolder}?sort=edited&order=desc`
const escape = (text: string) => new RegExp(text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'))

async function open(page: Page) {
  await page.goto('/login.jsp')
  await signIn(page)
  await expect(page.getByRole('heading', { name: 'Recent Documents' })).toBeVisible()
  await page.goto(folderPath())
  const list = main(page).getByRole('grid')
  // A document the other test leaves alone
  await expect(list.getByRole('row', { name: escape(NAMES.deleteTwo) })).toBeVisible()
  return list
}

const row = (list: Locator, text: string) => list.getByRole('row', { name: escape(text) })
const document = (n: number) => call<{ name: string; folder: { id: number; name: string } }>(api!.get(`/api/v1/documents/${n}`, authorized(token)), `document ${n}`)

test('makes a folder, renames a document in place, and moves another by the picker', async ({ page }) => {
  const list = await open(page)

  // New Folder asks for a name and shows the folder in the list
  await page.getByRole('button', { name: 'New Folder' }).click()
  const asking = page.getByRole('dialog', { name: 'New Folder' })
  await asking.getByRole('textbox', { name: 'Name' }).fill(MADE)
  await asking.getByRole('button', { name: 'Create' }).click()
  await expect(page.getByText(`Created ${MADE}`)).toBeVisible()
  await expect(row(list, MADE)).toBeVisible()

  // F2 edits the title in the row. Another extension is refused where the field is, and Esc leaves the name alone
  await row(list, NAMES.rename).getByRole('rowheader').click()
  await page.keyboard.press('F2')
  const field = page.getByRole('textbox', { name: `New name of ${NAMES.rename}` })
  await expect(field).toBeFocused()
  await field.fill(`Wrong ${RUN}.pdf`)
  await field.press('Enter')
  await expect(main(page).getByRole('alert')).toHaveText("The name has to end in .txt, as the document's does.")
  await expect(field).toBeVisible()
  await field.press('Escape')
  await expect(field).toHaveCount(0)
  expect((await document(numbers.rename)).name).toBe(NAMES.rename)
  await expect(row(list, NAMES.rename)).toBeFocused()

  // Without its extension the name keeps it
  await page.keyboard.press('F2')
  await field.fill(`Renamed ${RUN}`)
  await field.press('Enter')
  await expect(row(list, `Renamed ${RUN}.txt`)).toBeVisible()
  expect((await document(numbers.rename)).name).toBe(`Renamed ${RUN}.txt`)

  // More › Move To… starts from the matters, and goes into the matter's folders
  await row(list, NAMES.pick).getByRole('rowheader').click()
  await page.getByRole('button', { name: 'More', exact: true }).click()
  await page.getByRole('menuitem', { name: 'Move To…' }).click()
  const picker = page.getByRole('dialog', { name: 'Move 1 Document' })
  await picker.getByRole('button', { name: /Smoke Matter/ }).first().click()
  await picker.getByRole('button', { name: new RegExp(`^Documents`) }).click()
  await picker.getByRole('button', { name: escape(PICKED) }).click()
  await picker.getByRole('button', { name: 'Move Here' }).click()
  await expect(page.getByText(`Moved 1 document to ${PICKED}`)).toBeVisible()
  await expect(row(list, NAMES.pick)).toHaveCount(0)
  expect((await document(numbers.pick)).folder).toMatchObject({ id: folders.picked, name: PICKED })
})

test('moves two rows by dragging them onto a folder of the tree, and deletes two after the question', async ({ page }) => {
  const list = await open(page)
  const tree = page.getByRole('navigation', { name: 'Navigation' }).getByRole('treegrid', { name: 'My Matters' })
  // The tree opens down to the open folder's parent, Documents, so the folders in it show
  const target = tree.getByRole('row', { name: escape(DRAGGED) }).getByText(DRAGGED)
  await expect(target).toBeVisible()

  // The two rows, side by side, are selected; dragging one of them carries both
  await row(list, NAMES.dragTwo).getByRole('rowheader').click()
  await expect(list.getByRole('row', { selected: true })).toHaveCount(1)
  await row(list, NAMES.dragOne).getByRole('rowheader').click({ modifiers: ['Shift'] })
  await expect(list.getByRole('row', { selected: true })).toHaveCount(2)
  const carried = await row(list, NAMES.dragOne).evaluate((element) => {
    const transfer = new DataTransfer()
    element.dispatchEvent(new DragEvent('dragstart', { bubbles: true, cancelable: true, dataTransfer: transfer }))
    ;(window as unknown as { transfer: DataTransfer }).transfer = transfer
    return { types: [...transfer.types], data: transfer.getData('application/x-healdan-documents') }
  })
  expect(carried.types).toContain('application/x-healdan-documents')
  expect((JSON.parse(carried.data) as { docNumber: number }[]).map((one) => one.docNumber).sort()).toEqual([numbers.dragOne, numbers.dragTwo].sort())

  // Over the folder it lights up; dropped, both move there
  await target.evaluate((element) => {
    const transfer = (window as unknown as { transfer: DataTransfer }).transfer
    for (const type of ['dragenter', 'dragover']) element.dispatchEvent(new DragEvent(type, { bubbles: true, cancelable: true, dataTransfer: transfer }))
  })
  await expect(tree.locator('[data-drop-target="true"]')).toHaveCount(1)
  await target.evaluate((element) => {
    const transfer = (window as unknown as { transfer: DataTransfer }).transfer
    element.dispatchEvent(new DragEvent('drop', { bubbles: true, cancelable: true, dataTransfer: transfer }))
  })
  await expect(page.getByText(`Moved 2 documents to ${DRAGGED}`)).toBeVisible()
  await expect(row(list, NAMES.dragOne)).toHaveCount(0)
  await expect(row(list, NAMES.dragTwo)).toHaveCount(0)
  expect((await document(numbers.dragOne)).folder.id).toBe(folders.dragged)
  expect((await document(numbers.dragTwo)).folder.id).toBe(folders.dragged)

  // Delete asks first, naming both in the list's order (newest first), and says where the Trash is
  await row(list, NAMES.deleteTwo).getByRole('rowheader').click()
  // A range counts from the row clicked first, once the selection has followed the click into the address
  await expect(list.getByRole('row', { selected: true })).toHaveCount(1)
  await row(list, NAMES.deleteOne).getByRole('rowheader').click({ modifiers: ['Shift'] })
  // The address follows the selection a moment later: Delete is for both once both are selected
  await expect(list.getByRole('row', { selected: true })).toHaveCount(2)
  await page.keyboard.press('Delete')
  const question = page.getByRole('dialog', { name: 'Move 2 documents to the Trash?' })
  await expect(question.getByRole('listitem')).toHaveText([NAMES.deleteTwo, NAMES.deleteOne])
  await expect(question.getByText('The Trash is in the Classic Interface, where they can be restored.')).toBeVisible()
  await question.getByRole('button', { name: 'Cancel' }).click()
  expect((await document(numbers.deleteOne)).name).toBe(NAMES.deleteOne)

  await page.keyboard.press('Delete')
  await page.getByRole('dialog', { name: 'Move 2 documents to the Trash?' }).getByRole('button', { name: 'Move to Trash' }).click()
  await expect(page.getByText('Moved 2 documents to the Trash')).toBeVisible()
  await expect(row(list, NAMES.deleteOne)).toHaveCount(0)
  for (const key of ['deleteOne', 'deleteTwo'] as const) {
    const gone = await api!.get(`/api/v1/documents/${numbers[key]}`, authorized(token))
    expect(gone.status()).toBe(404)
  }
})
