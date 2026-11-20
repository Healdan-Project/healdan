// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { expect, test, type Locator, type Page } from '@playwright/test'
import { ADMIN_PASSWORD, ADMIN_USERNAME, PASSWORD, apiContext, authorized, call, deviceToken, escape, signIn } from './helpers'

// Healdan Admin's Phase 2 end to end (admin spec 8.2 to 8.5 and 17.1): the stack's admin makes a workspace template with a
// folder, Correspondence, holding Letters, which follows it, and Privileged, restricted to administrators; then a client,
// and from the client's page a matter from that template, in a Store made from New Matter that Everyone can see. Then Edit
// renames the matter, and the Workspace tab narrows Correspondence to Read Only for Everyone. Last, webuser, in the User
// app, sees the matter's Correspondence and Letters, and not Privileged, and can't file into Correspondence. Everything is
// made with this run's own names, in a Store, a template, a client and a matter of its own: nothing global changes, as
// the other specs share the stack (no change to webuser, Everyone or Smoke Garage), and nothing depends on what an earlier
// step or run left. Each browser runs it, so its names carry the project's.
test.describe.configure({ mode: 'serial' })

const ADMIN = { username: ADMIN_USERNAME, password: ADMIN_PASSWORD }
const FOLDERS = { open: 'Correspondence', inside: 'Letters', restricted: 'Privileged' }

/** This run's names, made in the first test */
let names = { template: '', client: '', store: '', matter: '', edited: '' }
/** The matter's own folder, as its page's Open in Healdan opens it in the User app */
let matterFolder = 0

const main = (page: Page) => page.getByRole('main')
const pane = (page: Page) => page.getByRole('navigation', { name: 'Navigation' })
const chosen = (page: Page) => main(page).getByRole('region', { name: 'Chosen Folder' })
const access = (page: Page) => chosen(page).getByRole('region', { name: 'Who Can Use This Folder' })
const tree = (page: Page) => main(page).getByRole('treegrid')
/** A folder's row in the Workspace Editor's tree: its name, then ", Own Permissions" for one with permissions of its own */
const node = (page: Page, name: string) => tree(page).getByRole('row', { name: new RegExp(`^${escape(name).source}(, Own Permissions)?$`) })
const toast = (page: Page, text: string) => expect(page.getByText(text, { exact: true }).first()).toBeVisible()

/** Opens a folder of the tree in the panel beside it */
async function choose(page: Page, name: string) {
  await node(page, name).click()
  await expect(chosen(page).getByRole('heading', { level: 2, name, exact: true })).toBeVisible()
}

/** New Folder in the folder chosen, which then has the new folder chosen */
async function newFolder(page: Page, inside: string, name: string) {
  await main(page).getByRole('button', { name: 'New Folder', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: `New Folder in ${inside}` })
  await dialog.getByRole('textbox', { name: 'Name' }).fill(name)
  await dialog.getByRole('button', { name: 'Create', exact: true }).click()
  await expect(dialog).toBeHidden()
  await expect(chosen(page).getByRole('heading', { level: 2, name, exact: true })).toBeVisible()
  await expect(node(page, name)).toBeVisible()
}

/** Add Person or Group: the group found by its name and picked, which joins the rows at Read Only */
async function addGroup(page: Page, group: string) {
  await access(page).getByRole('button', { name: 'Add Person or Group' }).click()
  const dialog = page.getByRole('dialog', { name: 'Add Person or Group' })
  await dialog.getByRole('combobox', { name: 'Find a Person or Group' }).fill(group)
  await dialog.getByRole('option', { name: group, exact: true }).click()
  await expect(dialog).toBeHidden()
  await expect(levelOf(page, group)).toHaveValue('read-only')
}

const levelOf = (page: Page, who: string) => access(page).getByRole('combobox', { name: `Access for ${who}` })

/** Saves who can use the folder chosen, and waits for the toast that says so */
async function saveAccess(page: Page, folder: string) {
  await access(page).getByRole('button', { name: 'Save', exact: true }).click()
  await toast(page, `Saved who can use ${folder}`)
}

/** A search field of a form (PickField): the text typed, the server's answer waited for, then the option picked */
async function pick(page: Page, field: Locator, text: string, answer: string, option: RegExp) {
  const answered = page.waitForResponse((response) => response.url().includes(answer) && response.request().method() === 'GET')
  await field.fill(text)
  await answered
  // The list opens on the next key once the answer has come (React Aria's combo box opens as its text changes)
  await field.press('ArrowDown')
  await page.getByRole('option', { name: option }).first().click()
}

test.beforeAll(() => {
  expect(PASSWORD, 'WEBUSER_PASSWORD is set by docker/tests/test-web.sh').not.toBe('')
  expect(ADMIN_PASSWORD, 'ADMIN_PASSWORD is set by docker/tests/test-web.sh').not.toBe('')
})

test('an administrator makes a template, a client, a Store and a matter, edits the matter and narrows a folder', async ({ page }, testInfo) => {
  // Four screens and seven dialogs, each asking the server
  test.setTimeout(300_000)
  const run = `${testInfo.project.name}-${Date.now().toString(36)}`
  names = { template: `E2E Template ${run}`, client: `E2E Client ${run}`, store: `E2E Store ${run}`, matter: `E2E Matter ${run}`, edited: `E2E Matter ${run} Edited` }

  await page.goto('/admin/templates')
  await expect(page).toHaveURL(/\/login\.jsp/)
  await signIn(page, ADMIN)
  await expect(page).toHaveURL(/\/admin\/templates$/)
  await expect(main(page).getByRole('heading', { level: 1, name: 'Workspace Templates' })).toBeVisible()

  await test.step('a template whose own folder Everyone can use, with Correspondence, Letters and a restricted Privileged', async () => {
    await main(page).getByRole('button', { name: 'New Template' }).click()
    const dialog = page.getByRole('dialog', { name: 'New Template' })
    await dialog.getByRole('textbox', { name: 'Name' }).fill(names.template)
    await expect(dialog.getByRole('combobox', { name: 'Start From' })).toHaveValue('')
    await dialog.getByRole('button', { name: 'Create Template' }).click()
    await expect(page).toHaveURL(/\/admin\/templates\/\d+$/)
    await expect(main(page).getByRole('heading', { level: 1, name: names.template })).toBeVisible()

    // Its own folder is chosen, and follows the Templates Store until it has permissions of its own
    await expect(chosen(page).getByRole('heading', { level: 2, name: names.template })).toBeVisible()
    await access(page).getByRole('button', { name: 'Set Own Permissions' }).click()
    await addGroup(page, 'Everyone')
    await levelOf(page, 'Everyone').selectOption({ label: 'Read/Write' })
    await saveAccess(page, names.template)
    await expect(node(page, names.template)).toHaveAccessibleName(`${names.template}, Own Permissions`)

    await newFolder(page, names.template, FOLDERS.open)
    await newFolder(page, FOLDERS.open, FOLDERS.inside)
    await choose(page, FOLDERS.open)
    await newFolder(page, FOLDERS.open, FOLDERS.restricted)
    // Privileged keeps out everyone but administrators: Everyone's row taken away
    await access(page).getByRole('button', { name: 'Set Own Permissions' }).click()
    await access(page).getByRole('button', { name: 'Remove Everyone' }).click()
    await expect(levelOf(page, 'Everyone')).toHaveCount(0)
    await saveAccess(page, FOLDERS.restricted)
    await expect(node(page, FOLDERS.restricted)).toHaveAccessibleName(`${FOLDERS.restricted}, Own Permissions`)
    // Nothing a new matter would refuse
    await expect(main(page).getByRole('heading', { name: 'What New Matter Would Refuse' })).toHaveCount(0)
  })

  await test.step('a client, from the Clients screen, which opens its page', async () => {
    await pane(page).getByRole('link', { name: 'Clients' }).click()
    await expect(main(page).getByRole('heading', { level: 1, name: 'Clients' })).toBeVisible()
    await main(page).getByRole('button', { name: 'New Client' }).click()
    const dialog = page.getByRole('dialog', { name: 'New Client' })
    // The next free number is suggested
    await expect(dialog.getByRole('textbox', { name: 'Number' })).toHaveValue(/^\d+$/)
    await dialog.getByRole('textbox', { name: 'Name' }).fill(names.client)
    await dialog.getByRole('button', { name: 'Create Client' }).click()
    await expect(page).toHaveURL(/\/admin\/clients\/\d+$/)
    await expect(main(page).getByRole('heading', { level: 1, name: escape(names.client) })).toBeVisible()
  })

  await test.step("a matter from the client's page, in a Store New Matter makes, which Everyone can see", async () => {
    await main(page).getByRole('button', { name: 'New Matter' }).click()
    const dialog = page.getByRole('dialog', { name: 'New Matter' })
    // Set to the client whose page it was opened from, its next free number suggested, and the name next to type
    await expect(dialog.getByRole('combobox', { name: 'Client' })).toHaveValue(escape(names.client))
    await expect(dialog.getByRole('textbox', { name: 'Number' })).toHaveValue('1')
    await expect(dialog.getByRole('textbox', { name: 'Name' })).toBeFocused()
    await dialog.getByRole('textbox', { name: 'Name' }).fill(names.matter)
    await dialog.getByRole('textbox', { name: 'Description' }).fill(`Made by admin-matters.spec, ${run}`)

    await dialog.getByRole('button', { name: 'New Store…' }).click()
    const store = page.getByRole('dialog', { name: 'New Store' })
    await store.getByRole('textbox', { name: 'Name' }).fill(names.store)
    // Each group is offered by its name and description
    await store.getByRole('listbox', { name: 'Groups' }).getByRole('option', { name: /^Everyone\b/ }).click()
    await expect(store.getByRole('button', { name: 'Remove Everyone' })).toBeVisible()
    await store.getByRole('button', { name: 'Create Store' }).click()
    await expect(store).toBeHidden()
    // The Store made is the one chosen
    const chosenStore = () => dialog.getByRole('combobox', { name: 'Store' }).evaluate((select: HTMLSelectElement) => select.selectedOptions[0]?.text)
    await expect.poll(chosenStore).toBe(names.store)

    await pick(page, dialog.getByRole('combobox', { name: 'Owner' }), 'admin', '/api/v1/users', /^Admin Admin\b/)
    await dialog.getByRole('combobox', { name: 'Template' }).selectOption({ label: names.template })
    // What it will contain: the template's folders, under the matter's own
    const contains = dialog.getByRole('region', { name: 'It Will Contain' })
    for (const folder of Object.values(FOLDERS)) await expect(contains.getByText(folder, { exact: true })).toBeVisible()

    // Once the server's check has found nothing to refuse
    const create = dialog.getByRole('button', { name: 'Create Matter' })
    await expect(create).toBeEnabled()
    await create.click()
    await expect(page).toHaveURL(/\/admin\/matters\/\d+$/)
    await expect(main(page).getByRole('heading', { level: 1, name: new RegExp(`^\\d+\\.1 ${escape(names.matter).source}$`) })).toBeVisible()
    await expect(main(page).getByText(names.store, { exact: true }).first()).toBeVisible()
  })

  await test.step("Edit renames the matter, and its page shows it", async () => {
    await main(page).getByRole('button', { name: 'Edit', exact: true }).click()
    const dialog = page.getByRole('dialog', { name: /^Edit \d+\.1$/ })
    await dialog.getByRole('textbox', { name: 'Name' }).fill(names.edited)
    await dialog.getByRole('button', { name: 'Save', exact: true }).click()
    await expect(dialog).toBeHidden()
    await expect(main(page).getByRole('heading', { level: 1, name: new RegExp(`^\\d+\\.1 ${escape(names.edited).source}$`) })).toBeVisible()
    const open = await main(page).getByRole('link', { name: 'Open in Healdan' }).getAttribute('href')
    expect(open, 'Open in Healdan opens the matter in the User app').toMatch(/^\/app\/folders\/\d+$/)
    matterFolder = Number(open!.split('/').pop())
  })

  await test.step('the Workspace tab: the template\'s folders and their permissions, and Correspondence narrowed to Read Only', async () => {
    await main(page).getByRole('tab', { name: 'Workspace' }).click()
    await expect(page).toHaveURL(/\?tab=workspace$/)
    // Copied from the template: Privileged with permissions of its own, the others following the matter's own folder
    await expect(node(page, FOLDERS.restricted)).toHaveAccessibleName(`${FOLDERS.restricted}, Own Permissions`)
    await expect(node(page, FOLDERS.open)).toHaveAccessibleName(FOLDERS.open)
    await choose(page, FOLDERS.open)
    await expect(access(page).getByText(/^It follows the permissions of /)).toBeVisible()
    await access(page).getByRole('button', { name: 'Set Own Permissions' }).click()
    await expect(levelOf(page, 'Everyone')).toHaveValue('read-write')
    await levelOf(page, 'Everyone').selectOption({ label: 'Read Only' })
    await saveAccess(page, FOLDERS.open)
    await expect(node(page, FOLDERS.open)).toHaveAccessibleName(`${FOLDERS.open}, Own Permissions`)
    // As the server keeps it, after a fresh load
    await page.reload()
    await choose(page, FOLDERS.open)
    await expect(levelOf(page, 'Everyone')).toHaveValue('read-only')
  })
})

test('webuser sees the matter\'s Correspondence and Letters, and not Privileged, and can\'t file into Correspondence', async ({ page, playwright }, testInfo) => {
  expect(matterFolder, 'the first test made the matter').toBeGreaterThan(0)
  await page.goto(`/app/folders/${matterFolder}`)
  await expect(page).toHaveURL(/\/login\.jsp/)
  await signIn(page)
  await expect(page).toHaveURL(new RegExp(`/app/folders/${matterFolder}$`))
  const list = main(page).getByRole('grid')
  await expect(main(page).getByRole('heading', { level: 1, name: escape(names.edited) })).toBeVisible()
  // A plain click opens a folder
  await list.getByRole('row', { name: escape(FOLDERS.open) }).getByRole('rowheader').click()
  await expect(main(page).getByRole('heading', { level: 1, name: FOLDERS.open })).toBeVisible()
  await expect(list.getByRole('row', { name: escape(FOLDERS.inside) })).toBeVisible()
  await expect(list.getByRole('row', { name: escape(FOLDERS.restricted) })).toHaveCount(0)
  const correspondence = Number(new URL(page.url()).pathname.split('/').pop())

  // As the API answers webuser: Privileged isn't among Correspondence's folders, and Correspondence takes no filing now
  const api = await apiContext(playwright.request, testInfo.project.use.baseURL)
  const token = await deviceToken(api, `adm${Date.now().toString(36)}`)
  try {
    const inside = await call<{ items: { name: string }[] }>(api.get(`/api/v1/folders/${correspondence}/children?type=folder`, authorized(token)), 'Correspondence\'s folders')
    expect(inside.items.map((item) => item.name)).toEqual([FOLDERS.inside])
    const folder = await call<{ canFile: boolean }>(api.get(`/api/v1/folders/${correspondence}`, authorized(token)), 'Correspondence')
    expect(folder.canFile, 'Read Only takes no filing').toBe(false)
  } finally {
    await api.delete('/api/v1/auth/tokens/current', authorized(token))
    await api.dispose()
  }
})
