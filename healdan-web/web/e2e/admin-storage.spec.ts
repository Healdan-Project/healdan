// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { expect, test, type Locator, type Page, type Request } from '@playwright/test'
import { ADMIN_PASSWORD, ADMIN_USERNAME, escaped, signIn } from './helpers'

// Healdan Admin's Phase 3 end to end (admin spec 8.6 and 17.1), as the stack's admin. The Backends screen shows the
// storage overlay's Smoke Garage reachable, and its page, which this spec only reads, and the storage settings with their
// limits, which it doesn't save, as they're the whole server's. Then a local-folder backend of this run's own is added,
// encrypted, its recovery key confirmed from the add's window, tested and removed; and a Store of this run's own is made
// on Original with New Store, Everyone able to see it, renamed, deleted, and restored from Deleted Stores. Nothing else
// changes: not Smoke Garage, its Store Smoke Cloud or matters 1.2 and 1.3 in it, whose files the smoke test's steps 5f
// and 8 check, and no other backend, Store or setting. Each browser runs it, so its names carry the project's.

const ADMIN = { username: ADMIN_USERNAME, password: ADMIN_PASSWORD }
const GARAGE = 'Smoke Garage'

/**
 * The local backend's folder, one for each browser, so that one's leftover never stands in the other's way. A new
 * backend's folder has to be there already (Test refuses one that isn't, and Add tests first), and this spec can't make
 * one in the app's container, so it's a folder every container of the image has, in none of its volumes, that the app's
 * user can write in, and apart from Original (repository/docs), staging (repository/staging), the read cache (cache), the
 * key files (conf/healdan-storage-keys) and the conf folder, as a backend's folder has to be. The backend writes its
 * marker and an empty .held folder there, and its removal takes the marker away again.
 */
const FOLDERS: Record<string, string> = { chromium: '/var/tmp', webkit: '/tmp' }
/** The start of the local backend's name, before the run's */
const FOLDER_NAME = 'E2E Folder'

const main = (page: Page) => page.getByRole('main')
const pane = (page: Page) => page.getByRole('navigation', { name: 'Navigation' })
const toast = (page: Page, text: string) => expect(page.getByText(text, { exact: true }).first()).toBeVisible()
/** A list's row, by its first column */
const row = (list: Locator, name: string) => list.getByRole('row').filter({ has: list.page().getByRole('rowheader', { name, exact: true }) })
/** What a fact of the page's says, by its term (the dt and dd of a description list) */
const fact = (page: Page, term: string) => main(page).locator('dt').filter({ hasText: new RegExp(`^${escaped(term)}$`) }).locator('xpath=following-sibling::dd[1]')

/** A request that changes something: anything but GET, HEAD and OPTIONS */
const writes = (request: Request) => !['GET', 'HEAD', 'OPTIONS'].includes(request.method())

interface Backend {
  id: number
  name: string
  kind: 'local' | 's3'
  path: string | null
}

/** The admin API, asked from the page, so the browser sends it with the web session as the app does */
async function adminApi<T>(page: Page, method: 'GET' | 'DELETE', path: string): Promise<{ status: number; body: T | null }> {
  return page.evaluate(
    async ({ method, path }) => {
      const answer = await fetch(`/api/v1/admin${path}`, { method, headers: method === 'GET' ? {} : { 'X-Healdan-Client': 'web' } })
      return { status: answer.status, body: answer.status === 200 ? await answer.json() : null }
    },
    { method, path },
  )
}

test.beforeAll(() => {
  expect(ADMIN_PASSWORD, 'ADMIN_PASSWORD is set by docker/tests/test-web.sh').not.toBe('')
})

test('the Backends screen: Smoke Garage reachable and its page, and the settings with their limits, changing nothing', async ({ page }) => {
  await page.goto('/admin/backends')
  await expect(page).toHaveURL(/\/login\.jsp/)
  await signIn(page, ADMIN)
  await expect(page).toHaveURL(/\/admin\/backends$/)
  await expect(main(page).getByRole('heading', { level: 1, name: 'Backends' })).toBeVisible()
  // From here on it only reads: no request it makes may change anything
  const changes: string[] = []
  page.on('request', (request) => {
    if (writes(request)) changes.push(`${request.method()} ${new URL(request.url()).pathname}`)
  })

  const list = main(page).getByRole('grid', { name: 'Backends' })
  await test.step('Original and Smoke Garage are listed, Smoke Garage reachable', async () => {
    await expect(row(list, 'Original')).toBeVisible()
    const garage = row(list, GARAGE)
    await expect(garage).toBeVisible()
    await expect(garage).toContainText('S3')
    // Once the checks since the start have reached it (step 9 restarts the app first), it's OK: Refresh asks again
    await expect(async () => {
      await main(page).getByRole('button', { name: 'Refresh' }).click()
      await expect(garage.getByText('OK', { exact: true })).toBeVisible({ timeout: 5_000 })
    }, 'Smoke Garage is reachable').toPass({ timeout: 90_000 })
    await expect(main(page).getByRole('heading', { level: 2, name: 'Files with No Store' })).toBeVisible()
  })

  await test.step('the settings, each with its limits, and a value outside them refused before Save', async () => {
    type Limits = Record<'holdDays' | 'cacheGb' | 'transfers', { min: number; max: number }>
    const asked = await adminApi<{ on: boolean; holdDays: number; cacheGb: number; transfers: number; limits: Limits }>(page, 'GET', '/storage/settings')
    expect(asked.status, 'GET /storage/settings').toBe(200)
    const settings = asked.body!
    expect(settings.on).toBe(true)
    const form = main(page).getByRole('region', { name: 'Settings' })
    const counts = new Intl.NumberFormat('en-US')
    const fields = [
      { label: 'Holding Period (Days)', setting: 'holdDays' },
      { label: 'Read Cache (GB)', setting: 'cacheGb' },
      { label: 'Parallel Transfers', setting: 'transfers' },
    ] as const
    for (const { label, setting } of fields) {
      const field = form.getByRole('textbox', { name: label })
      await expect(field).toHaveValue(String(settings[setting]))
      const { min, max } = settings.limits[setting]
      await expect(field).toHaveAccessibleDescription(new RegExp(`from ${counts.format(min)} to ${counts.format(max)}`))
    }
    const save = form.getByRole('button', { name: 'Save', exact: true })
    // Nothing changed yet
    await expect(save).toBeDisabled()
    // One past the most it takes is said beside the field, and Save stays greyed out; put back, nothing has changed
    const transfers = form.getByRole('textbox', { name: 'Parallel Transfers' })
    const { min, max } = settings.limits.transfers
    const range = `Enter a whole number from ${counts.format(min)} to ${counts.format(max)}.`
    await transfers.fill(String(max + 1))
    await expect(form.getByText(range, { exact: true })).toBeVisible()
    await expect(save).toBeDisabled()
    await transfers.fill(String(settings.transfers))
    await expect(form.getByText(range, { exact: true })).toHaveCount(0)
    await expect(save).toBeDisabled()
  })

  await test.step("Smoke Garage's page, only read: encrypted, its key confirmed, keeping Smoke Cloud's files", async () => {
    await row(list, GARAGE).click()
    await expect(page).toHaveURL(/\/admin\/backends\/\d+$/)
    await expect(main(page).getByRole('heading', { level: 1, name: GARAGE })).toBeVisible()
    await expect(fact(page, 'Kind')).toHaveText('S3')
    await expect(fact(page, 'Where')).toHaveText(/^healdan-smoke\/.* at http:\/\/garage:3900$/)
    await expect(fact(page, 'Encrypted')).toHaveText('Yes')
    await expect(fact(page, 'Recovery Key')).toHaveText('Confirmed')
    await expect(fact(page, 'Stores')).toContainText('Smoke Cloud')
    // The backend's actions are there, and none is pressed; a confirmed key has no Confirm Recovery Key
    for (const action of ['Test', 'Edit', 'Show Recovery Key', 'Enter Recovery Key', 'Key History', 'Remove'])
      await expect(main(page).getByRole('button', { name: action, exact: true })).toBeVisible()
    await expect(main(page).getByRole('button', { name: 'Confirm Recovery Key' })).toHaveCount(0)
    await main(page).getByRole('navigation', { name: 'Breadcrumb' }).getByRole('link', { name: 'Backends' }).click()
    await expect(main(page).getByRole('heading', { level: 1, name: 'Backends' })).toBeVisible()
  })

  expect(changes, 'the Backends screen and Smoke Garage, only read').toEqual([])
})

test('a local-folder backend of its own run: added encrypted, its recovery key confirmed, tested and removed', async ({ page }, testInfo) => {
  // Two dialogs, a Test, a page and a removal, each asking the server
  test.setTimeout(180_000)
  const run = `${testInfo.project.name}-${Date.now().toString(36)}`
  const name = `${FOLDER_NAME} ${run}`
  const folder = FOLDERS[testInfo.project.name] ?? '/var/tmp'

  await page.goto('/admin/backends')
  await expect(page).toHaveURL(/\/login\.jsp/)
  await signIn(page, ADMIN)
  await expect(main(page).getByRole('heading', { level: 1, name: 'Backends' })).toBeVisible()

  await test.step("an earlier run's backend in this browser's folder, left by a run stopped before it removed it, removed", async () => {
    const asked = await adminApi<{ backends: Backend[] }>(page, 'GET', '/backends')
    expect(asked.status, 'GET /backends').toBe(200)
    const left = asked.body!.backends.filter((one) => one.kind === 'local' && one.path === folder && one.name.startsWith(`${FOLDER_NAME} `))
    for (const one of left) {
      const removed = await adminApi(page, 'DELETE', `/backends/${one.id}`)
      expect(removed.status, `removing ${one.name}, an earlier run's`).toBe(204)
    }
    if (left.length > 0) await page.reload()
  })

  await test.step('Add Backend: a local folder, encrypted, which Test passes; then its recovery key, confirmed', async () => {
    await main(page).getByRole('button', { name: 'Add Backend' }).click()
    const dialog = page.getByRole('dialog', { name: 'Add a Storage Backend' })
    // S3 to begin with, as the Storage tab's window starts
    await expect(dialog.getByRole('radio', { name: 'S3' })).toBeChecked()
    // A radio and a checkbox are hidden under their labels, as a person would click the words
    await dialog.getByText('Local Folder', { exact: true }).click()
    await expect(dialog.getByRole('radio', { name: 'Local Folder' })).toBeChecked()
    // A local folder isn't encrypted until asked
    const encrypt = dialog.getByRole('checkbox', { name: 'Encrypt' })
    await expect(encrypt).not.toBeChecked()
    await dialog.getByRole('textbox', { name: 'Name' }).fill(name)
    await dialog.getByRole('textbox', { name: 'Path' }).fill(folder)
    await dialog.getByText('Encrypt', { exact: true }).click()
    await expect(encrypt).toBeChecked()
    await dialog.getByRole('button', { name: 'Test', exact: true }).click()
    await expect(dialog.getByRole('status')).toHaveText('Test passed.')
    await dialog.getByRole('button', { name: 'Add', exact: true }).click()

    // The add's answer carries the key, shown at once in a window of its own, which asks for its last group
    const key = page.getByRole('dialog', { name: `Recovery Key of ${name}` })
    await expect(key).toBeVisible()
    await expect(dialog).toBeHidden()
    const groups = (await key.getByRole('group', { name: 'Recovery Key' }).innerText()).trim().split(/\s+/)
    expect(groups.join(' '), 'thirteen groups of four').toMatch(/^[A-Z0-9]{4}( [A-Z0-9]{4}){12}$/)
    const lastGroup = key.getByRole('textbox', { name: 'Last Group' })
    await expect(lastGroup).toBeFocused()
    await lastGroup.fill(groups[groups.length - 1])
    await key.getByRole('button', { name: 'Confirm', exact: true }).click()
    await toast(page, `The recovery key of ${name} is confirmed: Stores can use it`)
    await expect(key).toBeHidden()
  })

  await test.step('its page: the folder, encrypted, its key confirmed and shown once, to admin; Test passes', async () => {
    await expect(page).toHaveURL(/\/admin\/backends\/\d+$/)
    await expect(main(page).getByRole('heading', { level: 1, name })).toBeVisible()
    await expect(fact(page, 'Kind')).toHaveText('Local Folder')
    await expect(fact(page, 'Where')).toHaveText(folder)
    await expect(fact(page, 'Encrypted')).toHaveText('Yes')
    await expect(fact(page, 'Recovery Key')).toHaveText('Confirmed')
    await expect(fact(page, 'Key Last Shown')).toHaveText(new RegExp(` to ${ADMIN_USERNAME}$`))
    await expect(fact(page, 'Stores')).toHaveText('None')
    await main(page).getByRole('button', { name: 'Test', exact: true }).click()
    await expect(main(page).getByText('Test passed.', { exact: true })).toBeVisible()
    await expect(main(page).getByText('OK', { exact: true })).toBeVisible()

    // The add's showing of the key is recorded, and nothing else
    await main(page).getByRole('button', { name: 'Key History' }).click()
    const history = page.getByRole('dialog', { name: `Key History of ${name}` })
    const shown = history.getByRole('row').filter({ hasText: 'Recovery Key Shown' })
    await expect(shown).toHaveCount(1)
    await expect(shown).toContainText(ADMIN_USERNAME)
    await expect(history.getByRole('row')).toHaveCount(2)
    await history.getByRole('button', { name: 'Close' }).click()
    await expect(history).toBeHidden()
  })

  await test.step('Remove, after the question, and it has left the list', async () => {
    await main(page).getByRole('button', { name: 'Remove', exact: true }).click()
    const question = page.getByRole('alertdialog', { name: `Remove ${name}?` })
    // An encrypted backend's question says its key file goes too
    await expect(question).toContainText('Its key file is deleted too.')
    await question.getByRole('button', { name: 'Remove', exact: true }).click()
    await toast(page, `Removed ${name}`)
    await expect(page).toHaveURL(/\/admin\/backends$/)
    const list = main(page).getByRole('grid', { name: 'Backends' })
    await expect(row(list, GARAGE)).toBeVisible()
    await expect(row(list, name)).toHaveCount(0)
    const asked = await adminApi<{ backends: Backend[] }>(page, 'GET', '/backends')
    expect(asked.body!.backends.filter((one) => one.path === folder), 'no backend is left in the folder').toEqual([])
  })
})

test('a Store of its own run: made on Original that Everyone can see, renamed, deleted and restored', async ({ page }, testInfo) => {
  // Four dialogs and three screens, each asking the server
  test.setTimeout(180_000)
  const run = `${testInfo.project.name}-${Date.now().toString(36)}`
  const store = `E2E Admin Store ${run}`
  const renamed = `E2E Admin Store ${run} Renamed`

  await page.goto('/admin/stores')
  await expect(page).toHaveURL(/\/login\.jsp/)
  await signIn(page, ADMIN)
  await expect(page).toHaveURL(/\/admin\/stores$/)
  await expect(main(page).getByRole('heading', { level: 1, name: 'Stores' })).toBeVisible()
  const list = main(page).getByRole('grid', { name: 'Stores' })
  // The smoke fixture's Store and the storage overlay's, which it leaves alone
  await expect(row(list, 'Smoke')).toBeVisible()
  await expect(row(list, 'Smoke Cloud')).toBeVisible()

  await test.step('New Store: its name, Everyone, and Original, where a new Store keeps its files unless told', async () => {
    await main(page).getByRole('button', { name: 'New Store' }).click()
    const dialog = page.getByRole('dialog', { name: 'New Store' })
    await dialog.getByRole('textbox', { name: 'Name' }).fill(store)
    // Each group is offered by its name and description
    await dialog.getByRole('listbox', { name: 'Groups' }).getByRole('option', { name: /^Everyone\b/ }).click()
    await expect(dialog.getByRole('button', { name: 'Remove Everyone' })).toBeVisible()
    const backend = dialog.getByRole('combobox', { name: 'Backend' })
    await expect.poll(() => backend.evaluate((select: HTMLSelectElement) => select.selectedOptions[0]?.text)).toBe('Original')
    await dialog.getByRole('button', { name: 'Create Store' }).click()
    await toast(page, `Created ${store}`)
    await expect(dialog).toBeHidden()
  })

  await test.step('its page: on Original, Everyone able to see it, no matter in it, and its tabs', async () => {
    await expect(page).toHaveURL(/\/admin\/stores\/\d+$/)
    await expect(main(page).getByRole('heading', { level: 1, name: store })).toBeVisible()
    await expect(fact(page, 'Backend')).toHaveText('Original')
    await expect(fact(page, 'Matters')).toHaveText('0')
    await expect(fact(page, 'Who Can Read')).toHaveText('Everyone')
    await expect(main(page).getByText('No matter is in this Store yet.')).toBeVisible()
    // The list takes the page's width, so its columns fit and it doesn't scroll sideways (#306)
    const matters = main(page).getByRole('grid', { name: `Matters in ${store}` })
    await expect.poll(() => matters.evaluate((grid) => grid.scrollWidth <= grid.clientWidth)).toBe(true)
    await main(page).getByRole('tab', { name: 'Who Can See It' }).click()
    await expect(page).toHaveURL(/\?tab=access$/)
    await expect(main(page).getByRole('list', { name: `Who Can See ${store}` }).getByRole('listitem')).toHaveText(['Everyone'])
    // Nothing changed, so nothing to save
    await expect(main(page).getByRole('button', { name: 'Save', exact: true })).toBeDisabled()
    await main(page).getByRole('tab', { name: 'Storage' }).click()
    await expect(page).toHaveURL(/\?tab=storage$/)
    await expect(main(page).getByRole('tabpanel')).toContainText('Original')
  })

  await test.step('Rename: its page says the new name', async () => {
    await main(page).getByRole('button', { name: 'Rename', exact: true }).click()
    const dialog = page.getByRole('dialog', { name: `Rename ${store}` })
    const field = dialog.getByRole('textbox', { name: 'Name' })
    await expect(field).toHaveValue(store)
    // Its own name is no change
    await expect(dialog.getByRole('button', { name: 'Rename', exact: true })).toBeDisabled()
    await field.fill(renamed)
    await dialog.getByRole('button', { name: 'Rename', exact: true }).click()
    await toast(page, `Renamed ${store} to ${renamed}`)
    await expect(dialog).toBeHidden()
    await expect(main(page).getByRole('heading', { level: 1, name: renamed })).toBeVisible()
  })

  await test.step('Delete, after the question: it leaves the list for Deleted Stores, in the Trash', async () => {
    await main(page).getByRole('button', { name: 'Delete', exact: true }).click()
    const question = page.getByRole('alertdialog', { name: `Move ${renamed} to the Trash?` })
    await expect(question).toContainText(`${renamed} goes to the Trash. It holds no matter.`)
    await question.getByRole('button', { name: 'Move to Trash' }).click()
    await toast(page, `Moved ${renamed} to the Trash`)
    await expect(page).toHaveURL(/\/admin\/stores$/)
    await expect(row(list, 'Smoke')).toBeVisible()
    await expect(row(list, renamed)).toHaveCount(0)
    const deleted = main(page).getByRole('table', { name: 'Deleted Stores' })
    const gone = deleted.getByRole('row').filter({ has: page.getByRole('cell', { name: renamed, exact: true }) })
    await expect(gone).toHaveCount(1)
    await expect(gone).toContainText('In the Trash')
  })

  await test.step('Restore, after the question: back in the list with its new name, on Original, Everyone able to see it', async () => {
    await main(page).getByRole('button', { name: `Restore ${renamed}` }).click()
    const question = page.getByRole('alertdialog', { name: `Restore ${renamed}?` })
    await expect(question).toContainText(`${renamed} comes back under the root, with everything its delete took.`)
    await question.getByRole('button', { name: 'Restore', exact: true }).click()
    await toast(page, `Restored ${renamed}`)
    await expect(question).toBeHidden()
    await expect(row(list, renamed)).toBeVisible()
    await expect(main(page).getByRole('button', { name: `Restore ${renamed}` })).toHaveCount(0)
    await row(list, renamed).click()
    await expect(main(page).getByRole('heading', { level: 1, name: renamed })).toBeVisible()
    await expect(fact(page, 'Backend')).toHaveText('Original')
    await expect(fact(page, 'Who Can Read')).toHaveText('Everyone')
    await pane(page).getByRole('link', { name: 'Stores' }).click()
    await expect(row(list, renamed)).toBeVisible()
  })
})
