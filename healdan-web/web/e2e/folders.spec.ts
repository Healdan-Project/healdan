// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { expect, test, type APIRequestContext, type Locator, type Page } from '@playwright/test'
import { PASSWORD, apiContext, authorized, call, deviceToken, escape, fileDocument, matterFolders, secondMatterFolders, signIn, specsFolder, tabTo } from './helpers'

// Renaming and moving folders in the User app (folder rename spec; folders and Trash spec 7.5): renaming by F2 in the list,
// from the My Matters tree's ⋯ and from the open folder's heading, once by keyboard alone, with a taken name refused beside the
// field; moving by a drag onto the tree, by Move To…, once by keyboard alone, by a pointer's own drag of one tree folder onto
// another, and out of the matter into matter 1.4 after the question; and, for webuser, who isn't an administrator, Rename,
// Move To… and Delete greyed out on matter 1.1's top-level Documents folder. The folders are this run's own, in a folder of
// its own inside the specs' E2E folder (helpers' specsFolder), so nothing earlier runs or smoke steps left is in their lists.
const RUN = `fld${Date.now().toString(36)}`
const NAMES = {
  home: `Folders ${RUN}`,
  byF2: `By F2 ${RUN}`,
  byTree: `By Tree ${RUN}`,
  byHeading: `By Heading ${RUN}`,
  byMenu: `By Menu ${RUN}`,
  keys: `Keys ${RUN}`,
  keyTarget: `Key Target ${RUN}`,
  taken: `Taken ${RUN}`,
  // Moves, all inside this folder, so the lists the renames look at hold only their own
  moves: `Moves ${RUN}`,
  dragMe: `Carry Me ${RUN}`,
  dropHere: `Landing Spot ${RUN}`,
  pickMe: `Pick Me ${RUN}`,
  pickTarget: `Pick Target ${RUN}`,
  keyMe: `Key Me ${RUN}`,
  keyDestination: `Key Destination ${RUN}`,
  treeDragMe: `Pointer Drag Me ${RUN}`,
  treeDropHere: `Pointer Drop Here ${RUN}`,
  crossMe: `Cross Me ${RUN}`,
}
const CROSS_DOCUMENT = `In cross ${RUN}.txt`

let api: APIRequestContext | undefined
let token = ''
let matterFolder = 0
let documentsFolder = 0
let secondMatterFolder = 0
let crossDocument = 0
const ids = Object.fromEntries(Object.keys(NAMES).map((key) => [key, 0])) as Record<keyof typeof NAMES, number>

test.beforeAll(async ({ playwright }, testInfo) => {
  expect(PASSWORD, 'WEBUSER_PASSWORD is set by docker/tests/test-web.sh').not.toBe('')
  api = await apiContext(playwright.request, testInfo.project.use.baseURL)
  token = await deviceToken(api, RUN)
  const where = await matterFolders(api, token)
  matterFolder = where.matter
  documentsFolder = where.documents
  const makeFolder = async (parentId: number, name: string) =>
    (await call<{ id: number }>(api!.post('/api/v1/folders', { ...authorized(token), data: { parentId, name } }), `making ${name}`)).id
  ids.home = await makeFolder(await specsFolder(api, token, documentsFolder), NAMES.home)
  for (const key of ['byF2', 'byTree', 'byHeading', 'byMenu', 'keys', 'taken'] as const) ids[key] = await makeFolder(ids.home, NAMES[key])
  ids.keyTarget = await makeFolder(ids.keys, NAMES.keyTarget)
  ids.moves = await makeFolder(ids.home, NAMES.moves)
  for (const key of ['dragMe', 'dropHere', 'pickMe', 'pickTarget', 'keyMe', 'keyDestination', 'treeDragMe', 'treeDropHere', 'crossMe'] as const) ids[key] = await makeFolder(ids.moves, NAMES[key])
  crossDocument = await fileDocument(api, token, ids.crossMe, CROSS_DOCUMENT, 'text/plain', Buffer.from(`cross ${RUN}`))
  // Matter 1.4 is a place to move to out of the matter, which the picker offers from My Matters
  secondMatterFolder = (await secondMatterFolders(api, token)).matter
  await call(api.put(`/api/v1/me/matters/${secondMatterFolder}`, authorized(token)), 'adding matter 1.4 to My Matters')
  // The tree is My Matters: it opens to the folder shown
  await call(api.put(`/api/v1/me/matters/${matterFolder}`, authorized(token)), 'adding the matter to My Matters')
})

test.afterAll(async () => {
  if (!api) return
  if (token) {
    await api.delete(`/api/v1/me/matters/${matterFolder}`, authorized(token))
    await api.delete(`/api/v1/me/matters/${secondMatterFolder}`, authorized(token))
    // The folder that moved into matter 1.4 goes to the Trash, so no run leaves a folder in a matter nothing else uses
    await api.delete(`/api/v1/folders/${ids.crossMe}`, authorized(token))
    await api.delete('/api/v1/auth/tokens/current', authorized(token))
  }
  await api.dispose()
})

const main = (page: Page) => page.getByRole('main')
const tree = (page: Page) => page.getByRole('treegrid', { name: 'My Matters' })
const field = (page: Page, name: string) => page.getByRole('textbox', { name: `New Name of ${name}` })
// The field closes only once its save has answered. Its typed text proves nothing: Playwright names a row or a heading by
// what is in it, an open field's value included, so either matches the new name before the server has it
const saved = (page: Page) => expect(page.getByRole('textbox', { name: /^New Name of / })).toHaveCount(0)
const parentOf = async (id: number) => {
  const view = await call<{ path: { id: number }[] }>(api!.get(`/api/v1/folders/${id}`, authorized(token)), `folder ${id}`)
  return view.path.at(-2)?.id
}
const nameOf = async (id: number) => (await call<{ name: string }>(api!.get(`/api/v1/folders/${id}`, authorized(token)), `folder ${id}`)).name

async function open(page: Page, folderId: number) {
  await page.goto('/login.jsp')
  await signIn(page)
  await expect(page.getByRole('heading', { name: 'Recent Documents' })).toBeVisible()
  await page.goto(`/app/folders/${folderId}`)
}

test('F2 renames a folder row in place, refuses a taken name beside the field, and the tree follows', async ({ page }) => {
  await open(page, ids.home)
  // The tree draws the open folder unexpanded, so open it to see this folder, which the rename has to refresh
  await tree(page).locator(`[data-tree-key="${ids.home}"]`).getByRole('button', { name: /expand/i }).click()
  await expect(tree(page).getByRole('row', { name: escape(NAMES.byF2) })).toBeVisible()
  const row: Locator = main(page).getByRole('grid').getByRole('row', { name: escape(NAMES.byF2) })
  // A click on a folder's row would open it
  await row.focus()
  await page.keyboard.press('F2')
  await expect(field(page, NAMES.byF2)).toBeFocused()
  await field(page, NAMES.byF2).fill(NAMES.taken)
  await page.keyboard.press('Enter')
  await expect(main(page).getByRole('alert')).toHaveText(`There's already a folder called ${NAMES.taken} here.`)
  const renamed = `Renamed by F2 ${RUN}`
  await field(page, NAMES.byF2).fill(renamed)
  await page.keyboard.press('Enter')
  await expect(main(page).getByRole('grid').getByRole('row', { name: escape(renamed) })).toBeVisible()
  await expect(tree(page).getByRole('row', { name: escape(renamed) })).toBeVisible()
  await saved(page)
  expect(await nameOf(ids.byF2)).toBe(renamed)
})

test("renames a folder from the tree's ⋯, and the tree, the heading and the breadcrumb follow", async ({ page }) => {
  await open(page, ids.byTree)
  const node = tree(page).getByRole('row', { name: escape(NAMES.byTree) })
  await node.hover()
  await node.getByRole('button', { name: `More for ${NAMES.byTree}` }).click()
  await page.getByRole('menuitem', { name: 'Rename' }).click()
  const renamed = `Renamed in the Tree ${RUN}`
  await expect(field(page, NAMES.byTree)).toBeFocused()
  await field(page, NAMES.byTree).fill(renamed)
  await page.keyboard.press('Enter')
  await expect(tree(page).getByRole('row', { name: escape(renamed) })).toBeVisible()
  await expect(main(page).getByRole('heading', { level: 1, name: renamed })).toBeVisible()
  await expect(page.getByRole('navigation', { name: 'Breadcrumb' }).locator('[aria-current="page"]')).toHaveText(renamed)
  await saved(page)
  expect(await nameOf(ids.byTree)).toBe(renamed)
})

test("renames the open folder from the ⋯ beside its name, and the heading, the tree and the breadcrumb follow", async ({ page }) => {
  await open(page, ids.byHeading)
  await main(page).getByRole('button', { name: `More for ${NAMES.byHeading}` }).click()
  await page.getByRole('menuitem', { name: 'Rename' }).click()
  const renamed = `Renamed by Its Heading ${RUN}`
  await expect(field(page, NAMES.byHeading)).toBeFocused()
  await field(page, NAMES.byHeading).fill(renamed)
  await page.keyboard.press('Enter')
  await expect(main(page).getByRole('heading', { level: 1, name: renamed })).toBeVisible()
  await expect(tree(page).getByRole('row', { name: escape(renamed) })).toBeVisible()
  await expect(page.getByRole('navigation', { name: 'Breadcrumb' }).locator('[aria-current="page"]')).toHaveText(renamed)
  await saved(page)
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
  await saved(page)
  expect(await nameOf(ids.byMenu)).toBe(renamed)
})

test('renames a folder by keyboard alone', async ({ page }) => {
  await open(page, ids.keys)
  const row = main(page).getByRole('grid').getByRole('row', { name: escape(NAMES.keyTarget) })
  // From the heading's ⋯, which Tab reaches from the frame's controls (keyboard.spec.ts walks those): the walk then counts
  // only the toolbar's, wherever the page load left the focus
  await tabTo(page, row, { from: main(page).getByRole('button', { name: `More for ${NAMES.keys}` }) })
  await page.keyboard.press('F2')
  await expect(field(page, NAMES.keyTarget)).toBeFocused()
  const renamed = `Renamed by Keys ${RUN}`
  await page.keyboard.press('ControlOrMeta+a')
  await page.keyboard.type(renamed)
  await page.keyboard.press('Enter')
  await expect(main(page).getByRole('grid').getByRole('row', { name: escape(renamed) })).toBeFocused()
  await saved(page)
  expect(await nameOf(ids.keyTarget)).toBe(renamed)
})

test("greys Rename, Move To… and Delete out on a matter's top-level folder for someone who isn't an administrator", async ({ page }) => {
  await open(page, documentsFolder)
  await main(page).getByRole('button', { name: 'More for Documents' }).click()
  await expect(page.getByRole('menuitem', { name: 'Rename' })).toBeDisabled()
  await expect(page.getByRole('menuitem', { name: 'Move To…' })).toBeDisabled()
  await expect(page.getByRole('menuitem', { name: 'Delete' })).toBeDisabled()
  await page.keyboard.press('Escape')
  const node = tree(page).locator(`[data-tree-key="${documentsFolder}"]`)
  await node.focus()
  await page.keyboard.press('F2')
  await expect(page.getByText("Only administrators can rename a matter's top-level folders.")).toBeVisible()
  await expect(main(page).getByRole('textbox')).toHaveCount(0)
  await expect(tree(page).getByRole('textbox')).toHaveCount(0)
  // Delete in the tree says who deletes it, and asks nothing
  await page.keyboard.press('Escape')
  await node.focus()
  await page.keyboard.press('Delete')
  await expect(page.getByText("Only administrators can delete a matter's top-level folders.")).toBeVisible()
  await expect(page.getByRole('dialog')).toHaveCount(0)
})

// Moving folders (folders and Trash spec 7.5)

const inTree = (page: Page, folderName: string) => tree(page).getByRole('row', { name: escape(folderName) })

/** The open folder's own row in the tree is drawn unexpanded: opening it shows the folders a move goes to */
async function openMoves(page: Page) {
  await open(page, ids.moves)
  await tree(page).locator(`[data-tree-key="${ids.moves}"]`).getByRole('button', { name: /expand/i }).click()
  await expect(inTree(page, NAMES.dropHere)).toBeVisible()
  return main(page).getByRole('grid')
}

/** The picker, walked down from matter 1.1 to the folder that holds the places: Documents, E2E, this run's Folders and Moves */
async function pickerAtMoves(page: Page, title: string) {
  const picker = page.getByRole('dialog', { name: title })
  await picker.getByRole('button', { name: /Smoke Matter/ }).first().click()
  await picker.getByRole('button', { name: /^Documents/ }).click()
  await picker.getByRole('button', { name: /^E2E/ }).click()
  await picker.getByRole('button', { name: escape(NAMES.home) }).click()
  await picker.getByRole('button', { name: escape(NAMES.moves) }).click()
  await expect(picker.getByRole('heading', { name: `Folders in ${NAMES.moves}` })).toBeVisible()
  return picker
}

test('moves a folder dragged onto a folder of the tree, which lights up', async ({ page }) => {
  const list = await openMoves(page)
  const target = inTree(page, NAMES.dropHere).getByText(NAMES.dropHere)
  // Playwright can't drag a row that carries the app's own type, so the events are a browser's (dragstart on the row, then
  // dragenter, dragover and drop on the folder) with the one DataTransfer the row's dragstart fills; the tree's own rows are
  // dragged by the pointer below
  const carried = await list.getByRole('row', { name: escape(NAMES.dragMe) }).evaluate((element) => {
    const transfer = new DataTransfer()
    element.dispatchEvent(new DragEvent('dragstart', { bubbles: true, cancelable: true, dataTransfer: transfer }))
    ;(window as unknown as { transfer: DataTransfer }).transfer = transfer
    return [...transfer.types]
  })
  expect(carried).toContain('application/x-healdan-documents')
  await target.evaluate((element) => {
    const transfer = (window as unknown as { transfer: DataTransfer }).transfer
    for (const type of ['dragenter', 'dragover']) element.dispatchEvent(new DragEvent(type, { bubbles: true, cancelable: true, dataTransfer: transfer }))
  })
  await expect(tree(page).locator('[data-drop-target="true"]')).toHaveCount(1)
  await target.evaluate((element) => {
    const transfer = (window as unknown as { transfer: DataTransfer }).transfer
    element.dispatchEvent(new DragEvent('drop', { bubbles: true, cancelable: true, dataTransfer: transfer }))
  })
  await expect(page.getByText(`Moved "${NAMES.dragMe}" to ${NAMES.dropHere}`)).toBeVisible()
  await expect(list.getByRole('row', { name: escape(NAMES.dragMe) })).toHaveCount(0)
  expect(await parentOf(ids.dragMe)).toBe(ids.dropHere)
})

test('moves a folder by Move To…, whose picker greys out the folder itself and where it is already', async ({ page }) => {
  const list = await openMoves(page)
  await list.getByRole('row', { name: escape(NAMES.pickMe) }).getByRole('rowheader').click({ button: 'right' })
  await page.getByRole('menu', { name: `Actions for ${NAMES.pickMe}` }).getByRole('menuitem', { name: 'Move To…' }).click()
  const picker = await pickerAtMoves(page, `Move "${NAMES.pickMe}"`)
  // Where it is: the folder itself is a choice that can't be opened, and Move Here says why it's greyed out
  await expect(picker.getByRole('button', { name: new RegExp(`^${escape(NAMES.pickMe).source}.*The folder itself`) })).toBeDisabled()
  await expect(picker.getByRole('button', { name: 'Move Here' })).toBeDisabled()
  await expect(picker.getByText('Already there')).toBeVisible()
  await picker.getByRole('button', { name: escape(NAMES.pickTarget) }).click()
  await expect(picker.getByRole('button', { name: 'Move Here' })).toBeEnabled()
  await picker.getByRole('button', { name: 'Move Here' }).click()
  await expect(page.getByText(`Moved "${NAMES.pickMe}" to ${NAMES.pickTarget}`)).toBeVisible()
  await expect(list.getByRole('row', { name: escape(NAMES.pickMe) })).toHaveCount(0)
  expect(await parentOf(ids.pickMe)).toBe(ids.pickTarget)
})

test('moves a folder by keyboard alone: the row, More, Move To… and the picker are all Tab, the arrow keys and Enter', async ({ page }) => {
  await open(page, ids.moves)
  const row = main(page).getByRole('grid').getByRole('row', { name: escape(NAMES.keyMe) })
  // From the heading's ⋯, as the rename above: the walk counts only the toolbar's stops. The list is one stop of Tab, and its
  // rows are walked by the arrow keys, which select as they move, and More acts on the selection
  const first = main(page).getByRole('grid').getByRole('row').nth(1)
  await tabTo(page, first, { from: main(page).getByRole('button', { name: `More for ${NAMES.moves}` }) })
  for (let i = 0; i < 12 && !(await row.evaluate((element) => element === document.activeElement)); i++) await page.keyboard.press('ArrowDown')
  await expect(row).toBeFocused()
  await expect(row).toHaveAttribute('aria-selected', 'true')
  await tabTo(page, main(page).getByRole('button', { name: 'More', exact: true }), { from: row })
  await page.keyboard.press('Enter')
  await page.getByRole('menuitem', { name: 'Move To…' }).focus() // the menu's own first key is ArrowDown
  await page.keyboard.press('Enter')
  const picker = page.getByRole('dialog', { name: `Move "${NAMES.keyMe}"` })
  await expect(picker).toBeVisible()
  for (const [step, shown] of [
    [/Smoke Matter/, /^Folders in .*Smoke Matter/],
    [/^Documents/, /^Folders in Documents$/],
    [/^E2E$/, /^Folders in E2E$/],
    [escape(NAMES.home), new RegExp(`^Folders in ${escape(NAMES.home).source}$`)],
    [escape(NAMES.moves), new RegExp(`^Folders in ${escape(NAMES.moves).source}$`)],
    [escape(NAMES.keyDestination), new RegExp(`^Folders in ${escape(NAMES.keyDestination).source}$`)],
  ] as const) {
    // The picker's buttons are a list of choices: Tab to each
    await tabTo(page, picker.getByRole('button', { name: step }).first(), { limit: 300 })
    await page.keyboard.press('Enter')
    await expect(picker.getByRole('heading', { name: shown })).toBeVisible()
  }
  await tabTo(page, picker.getByRole('button', { name: 'Move Here' }))
  await page.keyboard.press('Enter')
  await expect(page.getByText(`Moved "${NAMES.keyMe}" to ${NAMES.keyDestination}`)).toBeVisible()
  await expect(row).toHaveCount(0)
  expect(await parentOf(ids.keyMe)).toBe(ids.keyDestination)
})

test("moves a folder of the tree dragged by the pointer onto another, as a person drags one", async ({ page }) => {
  await openMoves(page)
  // A real drag, with the pointer: the tree's own rows are what a person drags, and the browser makes the events
  // A person takes a folder by the start of its name: the middle of a long one is under the row's ⋯, which shows on hover
  await inTree(page, NAMES.treeDragMe)
    .getByText(NAMES.treeDragMe)
    .dragTo(inTree(page, NAMES.treeDropHere).getByText(NAMES.treeDropHere), { sourcePosition: { x: 6, y: 6 }, targetPosition: { x: 6, y: 6 } })
  await expect(page.getByText(`Moved "${NAMES.treeDragMe}" to ${NAMES.treeDropHere}`)).toBeVisible()
  await expect(main(page).getByRole('grid').getByRole('row', { name: escape(NAMES.treeDragMe) })).toHaveCount(0)
  expect(await parentOf(ids.treeDragMe)).toBe(ids.treeDropHere)
})

test('asks before a folder leaves its matter, and its documents then show the other matter', async ({ page }) => {
  const list = await openMoves(page)
  await list.getByRole('row', { name: escape(NAMES.crossMe) }).getByRole('rowheader').click({ button: 'right' })
  await page.getByRole('menu', { name: `Actions for ${NAMES.crossMe}` }).getByRole('menuitem', { name: 'Move To…' }).click()
  const picker = page.getByRole('dialog', { name: `Move "${NAMES.crossMe}"` })
  await picker.getByRole('button', { name: /Second Matter/ }).first().click()
  await picker.getByRole('button', { name: /^Documents/ }).click()
  await picker.getByRole('button', { name: 'Move Here' }).click()

  const question = page.getByRole('dialog', { name: new RegExp(`^Move "${escape(NAMES.crossMe).source}" into .*Second Matter\\?$`) })
  await expect(question.getByText(/^Its 1 document will take 1\.4's client and matter, and the properties its template sets\.$/)).toBeVisible()
  // Cancel leaves it where it was, and its document in matter 1.1
  await expect(question.getByRole('button', { name: 'Move', exact: true })).toBeFocused()
  await question.getByRole('button', { name: 'Cancel' }).click()
  await expect(question).toHaveCount(0)
  expect(await parentOf(ids.crossMe)).toBe(ids.moves)

  await list.getByRole('row', { name: escape(NAMES.crossMe) }).getByRole('rowheader').click({ button: 'right' })
  await page.getByRole('menu', { name: `Actions for ${NAMES.crossMe}` }).getByRole('menuitem', { name: 'Move To…' }).click()
  const again = page.getByRole('dialog', { name: `Move "${NAMES.crossMe}"` })
  await again.getByRole('button', { name: /Second Matter/ }).first().click()
  await again.getByRole('button', { name: /^Documents/ }).click()
  await again.getByRole('button', { name: 'Move Here' }).click()
  await page.getByRole('dialog', { name: /into .*Second Matter\?$/ }).getByRole('button', { name: 'Move', exact: true }).click()
  await expect(page.getByText(`Moved "${NAMES.crossMe}" to Documents`)).toBeVisible()
  await expect(list.getByRole('row', { name: escape(NAMES.crossMe) })).toHaveCount(0)

  // The document in it took matter 1.4
  const moved = await call<{ matter: { display: string; client: { number: number } } | null; folder: { id: number } }>(
    api!.get(`/api/v1/documents/${crossDocument}`, authorized(token)),
    'the document that moved',
  )
  expect(moved.folder.id).toBe(ids.crossMe)
  expect(moved.matter?.display).toContain('Second Matter')
  expect(moved.matter?.display).toMatch(/^1\.4\b/)
})
