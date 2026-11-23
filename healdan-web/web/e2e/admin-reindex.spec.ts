// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { expect, test, type BrowserContext, type Page, type Response } from '@playwright/test'
import { ADMIN_PASSWORD, ADMIN_USERNAME, escape, escaped, signIn, tabTo } from './helpers'

// Healdan Admin's Phase 6 end to end (admin spec 8.9, 13 and 17.1), as the stack's admin, on a folder of this run's own,
// `Reindex <run>` in the specs' E2E folder in matter 1.1, holding a folder `Filed <run>` with three text documents it files
// through the API, each holding a word of this run's own that only their contents have. Once search finds the word, the
// Search Index's Reindex dialog, driven by the keyboard alone, reindexes `Reindex <run>` by Folder (matter 1.1 found, the
// folder picked), its count the three documents; the reindex's card shows, then says it reindexed them, search finding the
// word between the start and the end and after, and History lists it. In the User app, a right-click on `Filed <run>`
// offers Reindex, whose question names it with its count; the toast's View Progress opens the Search Index in a new tab,
// and the reindex ends. A matter's page and the Workspace Editor's folder each open the dialog on what they're for, which
// is cancelled. The Audit Trail has `reindex.started` for the folder, by admin. It changes nothing global: it never ticks
// Empty the index first, reindexes Everything or a Store, presses Run Indexer Now (the Indexer's 5 seconds are the smoke
// test's step 9's) or tries again or skips a document, and its reindexes cover only its own documents, whose entries stay
// in the index while they're replaced. Its API calls go through the admin's web session in the page, as the apps' do: a
// device token's sign-in from a client LogicalDOC hasn't seen sends the account a new-device email, which a stack with no
// mail server logs as an error. Nothing it asks of the server is logged as an error. Each browser runs it, so its names
// carry the project's.
test.describe.configure({ mode: 'serial' })

const ADMIN = { username: ADMIN_USERNAME, password: ADMIN_PASSWORD }
/** The documents it files, and so the count each reindex of its own covers */
const FILED = 3

let run = ''
/** The word only this run's documents hold, in their contents */
let word = ''
let names = { folder: '', inside: '' }
let folderId = 0
let docNumbers: number[] = []

let context: BrowserContext | undefined
let page: Page

const main = (on: Page) => on.getByRole('main')

/**
 * A call to the API through the admin's web session, from the page, as the apps make it: JSON in, and out unless `204`; a
 * file instead, sent as the filing's `multipart/form-data`
 */
async function apiCall<T>(method: string, path: string, body?: { json?: unknown; file?: { name: string; type: string; text: string; document: unknown } }): Promise<T> {
  return page.evaluate(
    async ({ method, path, body }) => {
      let sent: BodyInit | undefined
      const headers: Record<string, string> = method === 'GET' ? {} : { 'X-Healdan-Client': 'web' }
      if (body?.file) {
        const form = new FormData()
        form.append('document', new Blob([JSON.stringify(body.file.document)], { type: 'application/json' }), 'document.json')
        form.append('file', new Blob([body.file.text], { type: body.file.type }), body.file.name)
        sent = form
      } else if (body?.json !== undefined) {
        sent = JSON.stringify(body.json)
        headers['Content-Type'] = 'application/json'
      }
      const answer = await fetch(`/api/v1${path}`, { method, headers, body: sent })
      if (!answer.ok) throw new Error(`${method} /api/v1${path}: HTTP ${answer.status}`)
      return answer.status === 204 ? undefined : await answer.json()
    },
    { method, path, body },
  ) as Promise<T>
}

/** The doc #s of this run's documents that a search for its word finds now */
async function found(): Promise<number[]> {
  const hits = await apiCall<{ items: { type: string; docNumber?: number }[] }>('GET', `/search?scope=documents&q=${encodeURIComponent(word)}&limit=50`)
  return hits.items.flatMap((hit) => (hit.type === 'document' && hit.docNumber !== undefined ? [hit.docNumber] : [])).sort((a, b) => a - b)
}

/** Search finds every document of this run's own by its word: asked again until it does, as the Indexer runs every 5 seconds */
async function searchFindsThem(what: string) {
  await expect.poll(found, { message: `search finds the run's documents ${what}`, timeout: 90_000, intervals: [1_000, 2_000, 3_000] }).toEqual(docNumbers)
}

interface Reindex {
  state: string
  name: string
  total: number
  done: number
  failed: number
  skipped: number
}

/** A reindex as the server has it now, read through the admin's web session in a page, as the Admin app reads it */
async function reindexOf(on: Page, id: number): Promise<Reindex> {
  return on.evaluate(async (job) => {
    const answer = await fetch(`/api/v1/admin/reindex/${job}`)
    if (!answer.ok) throw new Error(`GET /api/v1/admin/reindex/${job}: HTTP ${answer.status}`)
    return (await answer.json()) as Reindex
  }, id)
}

/** The start's answer, `201` with the reindex, waited for while `start` presses what starts it; the reindex's id */
async function startedBy(on: Page, start: () => Promise<unknown>): Promise<number> {
  const isStart = (response: Response) => response.request().method() === 'POST' && new URL(response.url()).pathname === '/api/v1/admin/reindex'
  const [answer] = await Promise.all([on.waitForResponse(isStart), start()])
  expect(answer.status(), 'the reindex started').toBe(201)
  return ((await answer.json()) as { id: number }).id
}

/** A folder's subfolder by its name, as helpers' specsFolder pages through them, or none */
async function subfolder(parentId: number, name: string): Promise<number | undefined> {
  for (let offset = 0; ; ) {
    const inside = await apiCall<{ items: { id: number; name: string; type: string }[]; total?: number }>('GET', `/folders/${parentId}/children?type=folder&limit=200&offset=${offset}`)
    const one = inside.items.find((item) => item.type === 'folder' && item.name === name)
    if (one) return one.id
    offset += inside.items.length
    if (inside.items.length === 0 || (inside.total !== undefined && offset >= inside.total)) return undefined
  }
}

const makeFolder = async (parentId: number, name: string) => (await apiCall<{ id: number }>('POST', '/folders', { json: { parentId, name } })).id

test.beforeAll(async ({ browser }, testInfo) => {
  expect(ADMIN_PASSWORD, 'ADMIN_PASSWORD is set by docker/tests/test-web.sh').not.toBe('')
  // A time and a random part, so two runs, or the two browsers, never share a name or a word
  const stamp = `${Date.now().toString(36)}${Math.floor(Math.random() * 36 ** 4).toString(36)}`
  run = `rdx-${testInfo.project.name}-${stamp}`
  word = `reindexword${stamp}`
  names = { folder: `Reindex ${run}`, inside: `Filed ${run}` }
  context = await browser.newContext(testInfo.project.use)
  page = await context.newPage()
  await page.goto('/admin/search-index')
  await expect(page).toHaveURL(/\/login\.jsp/)
  await signIn(page, ADMIN)
  await expect(page).toHaveURL(/\/admin\/search-index$/)

  // Matter 1.1, its Documents folder (which the smoke fixture makes) and the specs' E2E folder in it, found or made
  const matters = await apiCall<{ items: { number: number; client: { number: number }; folderId: number }[] }>('GET', '/search?scope=matters&q=1.1')
  const matter = matters.items.find((one) => one.client.number === 1 && one.number === 1)
  expect(matter, 'the smoke fixture has matter 1.1').toBeTruthy()
  const documents = await subfolder(matter!.folderId, 'Documents')
  expect(documents, 'matter 1.1 has a Documents folder').toBeDefined()
  const e2e = (await subfolder(documents!, 'E2E')) ?? (await makeFolder(documents!, 'E2E'))
  folderId = await makeFolder(e2e, names.folder)
  const inside = await makeFolder(folderId, names.inside)
  // The word is in the contents alone, so a hit is the index's
  const filed: number[] = []
  for (const letter of ['A', 'B', 'C'].slice(0, FILED)) {
    const file = { name: `Note ${letter} ${run}.txt`, type: 'text/plain', text: `Note ${letter}, which holds ${word}.`, document: { folderId: inside, checkOut: false } }
    filed.push((await apiCall<{ docNumber: number }>('POST', '/documents', { file })).docNumber)
  }
  docNumbers = filed.sort((a, b) => a - b)
  await searchFindsThem('once the Indexer has read them')
})

test.afterAll(async () => {
  await context?.close()
})

/** The reindexes of the run's folder and of the folder inside it, as the server names them */
const folderJob = () => `Folder ${names.folder} in 1.1 Smoke Matter`
const insideJob = () => `Folder ${names.inside} in 1.1 Smoke Matter`
/** A reindex's card above the Search Index's tabs, by what it covers, in any state: its title ends with the reindex's name */
const card = (name: string) => main(page).getByRole('region', { name: new RegExp(`^(Waiting to Reindex|Reindexing|Reindexed) ${escaped(name)}$`) })

test("reindexes the run's folder from the Search Index's Reindex dialog, by the keyboard alone, search finding its documents throughout", async () => {
  test.setTimeout(150_000)
  await page.goto('/admin/search-index')
  const heading = main(page).getByRole('heading', { level: 1, name: 'Search Index' })
  await expect(heading).toBeVisible()
  await expect(main(page).getByText('Loading…')).toHaveCount(0)

  // From the page's heading, by Tab, to Reindex…, which opens the dialog on Matter
  const reindex = main(page).getByRole('button', { name: 'Reindex…' })
  await tabTo(page, reindex, { from: heading })
  await page.keyboard.press('Enter')
  await expect(page).toHaveURL(/[?&]reindex=matter(&|$)/)
  const dialog = page.getByRole('dialog', { name: 'Reindex' })
  await expect(dialog).toBeVisible()
  const choices = dialog.getByRole('radiogroup', { name: 'What to Reindex' })
  const matter = choices.getByRole('radio', { name: 'Matter' })
  const folder = choices.getByRole('radio', { name: 'Folder' })
  await expect(matter).toBeChecked()

  // The dialog opens with the focus on itself; Tab goes into the choices, the arrows to Folder, and Space picks it
  await expect(dialog).toBeFocused()
  await page.keyboard.press('Tab')
  expect(await choices.evaluate((group) => group.contains(document.activeElement)), 'Tab goes into the choices').toBe(true)
  for (let i = 0; i < 5 && !(await folder.evaluate((radio) => radio === document.activeElement)); i++) await page.keyboard.press('ArrowRight')
  await expect(folder).toBeFocused()
  await page.keyboard.press('Space')
  await expect(folder).toBeChecked()

  // Matter 1.1 found by its number, then picked by the arrows and Enter
  const find = dialog.getByRole('textbox', { name: 'Matter' })
  await tabTo(page, find)
  await page.keyboard.type('1.1')
  await expect(dialog.getByRole('option', { name: /^1\.1 – Smoke Matter/ })).toBeVisible()
  await page.keyboard.press('ArrowDown')
  await page.keyboard.press('Enter')
  await expect(dialog.getByText('Matter: 1.1 – Smoke Matter')).toBeVisible()

  // The focus goes on to the matter's folders, whose filter finds the run's folder, picked as the matter was
  const filter = dialog.getByRole('textbox', { name: 'Folder' })
  await expect(filter).toBeFocused()
  await page.keyboard.type(names.folder)
  await expect(dialog.getByRole('option', { name: escape(names.folder) })).toBeVisible()
  await page.keyboard.press('ArrowDown')
  await page.keyboard.press('Enter')

  // The count is the run's documents, the folder inside included
  const button = dialog.getByRole('button', { name: `Reindex ${FILED} Documents` })
  await expect(button).toBeEnabled()
  await tabTo(page, button)
  const job = await startedBy(page, () => page.keyboard.press('Enter'))
  await expect(dialog).toBeHidden()
  await expect(page).toHaveURL(/\/admin\/search-index$/)

  // Its card at once, as the start answered; search still finds the documents while it runs
  await expect(card(folderJob())).toBeVisible()
  expect(await found(), 'search finds the documents between the start and the end').toEqual(docNumbers)
  // Its end, which the card says, with how many it reindexed
  const ended = main(page).getByRole('region', { name: `Reindexed ${folderJob()}` })
  await expect(ended).toBeVisible({ timeout: 60_000 })
  await expect(ended).toContainText(`${FILED} reindexed.`)
  expect(await reindexOf(page, job)).toMatchObject({ state: 'done', name: folderJob(), total: FILED, done: FILED, failed: 0, skipped: 0 })
  await searchFindsThem('after the reindex')

  // History lists it, with how it went
  await main(page).getByRole('tab', { name: 'History' }).click()
  const history = main(page).getByRole('table', { name: 'Reindexes' })
  await expect(history.getByRole('row').filter({ has: page.getByRole('rowheader', { name: folderJob(), exact: true }) })).toContainText(`Done: ${FILED} reindexed`)
})

test("reindexes the folder inside it from a right-click in the User app, whose toast's View Progress opens the Search Index in a new tab", async () => {
  test.setTimeout(150_000)
  const app = await context!.newPage()
  try {
    await app.goto(`/app/folders/${folderId}`)
    const row = main(app).getByRole('grid').getByRole('row', { name: escape(names.inside) })
    await expect(row).toBeVisible()
    await row.getByRole('rowheader').click({ button: 'right' })
    await app.getByRole('menu', { name: `Actions for ${names.inside}` }).getByRole('menuitem', { name: 'Reindex' }).click()

    // The question names the folder as the server does, with its count, once the preview has come
    const question = app.getByRole('dialog', { name: 'Reindex' })
    const confirm = question.getByRole('button', { name: `Reindex ${FILED} Documents` })
    await expect(confirm).toBeEnabled()
    await expect(confirm).toBeFocused()
    await expect(question).toContainText(insideJob())
    await expect(question).toContainText(`${FILED} documents will be indexed again.`)
    // Its words are once in what a screen reader reads, the tree read once: the dialog is described by them, and no other
    // copy of them is anywhere, React Aria's announcer included
    await expect(question).toHaveAccessibleDescription(new RegExp(`^${escaped(insideJob())}`))
    const tree = await app.locator('body').ariaSnapshot()
    expect(tree.split(insideJob()).length - 1, `the question's name in the page's tree:\n${tree}`).toBe(1)
    expect(tree.split(`${FILED} documents will be indexed again.`).length - 1, `the question's count in the page's tree:\n${tree}`).toBe(1)

    const job = await startedBy(app, () => confirm.click())
    await expect(app.getByText(`Reindexing ${FILED} documents.`)).toBeVisible()
    const progress = app.getByRole('link', { name: 'View Progress' })
    await expect(progress).toHaveAttribute('href', '/admin/search-index')
    await expect(progress).toHaveAttribute('target', '_blank')
    const [opened] = await Promise.all([context!.waitForEvent('page'), progress.click()])
    try {
      await expect(opened).toHaveURL(/\/admin\/search-index$/)
      await expect(main(opened).getByRole('heading', { level: 1, name: 'Search Index' })).toBeVisible()
    } finally {
      await opened.close()
    }
    await expect.poll(async () => (await reindexOf(app, job)).state, { message: 'the reindex ends', timeout: 60_000 }).toBe('done')
    expect(await reindexOf(app, job)).toMatchObject({ name: insideJob(), total: FILED, done: FILED, failed: 0, skipped: 0 })
    await searchFindsThem('after the reindex from the User app')
  } finally {
    await app.close()
  }
})

test("a matter's page and the Workspace Editor's folder open the Reindex dialog on what they're for", async () => {
  const pane = page.getByRole('navigation', { name: 'Navigation' })
  const dialog = page.getByRole('dialog', { name: 'Reindex' })
  // It opens and cancels only: no reindex is started here
  const starts: string[] = []
  const noted = (request: { method(): string; url(): string }) => {
    if (request.method() === 'POST' && new URL(request.url()).pathname === '/api/v1/admin/reindex') starts.push(request.url())
  }
  page.on('request', noted)
  try {
    await pane.getByRole('link', { name: 'Matters' }).click()
    await main(page).getByRole('grid', { name: 'Matters' }).getByRole('row', { name: /^1\.1 Smoke Matter/ }).click()
    await expect(main(page).getByRole('heading', { level: 1, name: '1.1 Smoke Matter' })).toBeVisible()
    // The matter's own, beside its name, not the Workspace's for its chosen folder
    await main(page).getByRole('link', { name: 'Reindex', exact: true }).and(page.locator('[href*="reindex=matter"]')).click()
    await expect(page).toHaveURL(/\/admin\/search-index\?reindex=matter&id=\d+$/)
    await expect(dialog.getByRole('radio', { name: 'Matter' })).toBeChecked()
    await expect(dialog.getByRole('option', { name: /^1\.1 – Smoke Matter/, selected: true })).toBeVisible()
    await expect(dialog.getByRole('button', { name: /^Reindex [\d,]+ Documents?$/ })).toBeEnabled()
    await dialog.getByRole('button', { name: 'Cancel' }).click()
    await expect(dialog).toBeHidden()
    await expect(page).toHaveURL(/\/admin\/search-index$/)

    await page.goBack()
    await expect(main(page).getByRole('heading', { level: 1, name: '1.1 Smoke Matter' })).toBeVisible()
    await main(page).getByRole('tab', { name: 'Workspace' }).click()
    await main(page).getByRole('treegrid').getByRole('row', { name: /^Documents(, Own Permissions)?$/ }).click()
    await main(page).getByRole('region', { name: 'Chosen Folder' }).getByRole('link', { name: 'Reindex', exact: true }).click()
    await expect(page).toHaveURL(/\/admin\/search-index\?reindex=folder&id=\d+&matter=\d+$/)
    await expect(dialog.getByRole('radio', { name: 'Folder' })).toBeChecked()
    await expect(dialog.getByText('Matter: 1.1 – Smoke Matter')).toBeVisible()
    await expect(dialog.getByRole('option', { name: /^Documents/, selected: true })).toBeVisible()
    await expect(dialog.getByRole('button', { name: /^Reindex [\d,]+ Documents?$/ })).toBeEnabled()
    await page.keyboard.press('Escape')
    await expect(dialog).toBeHidden()
  } finally {
    page.off('request', noted)
  }
  expect(starts, 'no reindex started').toEqual([])
})

test("the Audit Trail has the folder's reindex, started by admin", async () => {
  await page.goto('/admin/audit')
  await expect(main(page).getByRole('heading', { level: 1, name: 'Audit Trail' })).toBeVisible()
  const kinds = main(page).getByRole('group', { name: 'Kind' })
  await kinds.getByText('Administration', { exact: true }).click()
  await expect(kinds.getByRole('checkbox', { name: 'Administration' })).toBeChecked()
  await main(page).getByRole('group', { name: 'Filters' }).getByRole('textbox', { name: 'Doc #, Name or Address' }).fill(names.folder)
  const rows = main(page).getByRole('grid', { name: 'Audit Trail' }).getByRole('row').filter({ has: page.getByRole('rowheader') })
  const started = rows.filter({ has: page.getByRole('gridcell', { name: 'Started a reindex', exact: true }) })
  await expect(started).toHaveCount(1)
  await expect(started).toContainText(folderJob())
  // Who: admin, by the name the stack gives them
  const me = await page.evaluate(async () => (await (await fetch('/api/v1/me')).json()) as { username: string; name: string })
  expect(me.username).toBe(ADMIN_USERNAME)
  await expect(started.getByRole('gridcell', { name: me.name, exact: true })).toBeVisible()
})
