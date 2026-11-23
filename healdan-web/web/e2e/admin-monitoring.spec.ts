// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { readFileSync } from 'node:fs'
import { expect, test, type APIRequestContext, type BrowserContext, type Locator, type Page, type Request } from '@playwright/test'
import {
  ADMIN_PASSWORD,
  ADMIN_USERNAME,
  PASSWORD,
  apiContext,
  authorized,
  call,
  deviceToken,
  downloadBy,
  escaped,
  fileDocument,
  matterFolders,
  minimalPdf,
  signIn,
  specsFolder,
} from './helpers'

// Healdan Admin's Phase 5 end to end (admin spec 8.8 and 17.1), as the stack's admin. With the Audit Trail open, a group
// of this run's own is made and renamed in a second tab, and Refresh finds the rename, with the old and the new name, in
// its row and its details; Export CSV downloads the rows the filters keep. webuser, in a browser of their own, selects
// one PDF of this run's own and reads another in Quick Look, twice: the Audit Trail then has one Viewed row for the one
// read and none for the one only selected. The Server Log follows the main log, keeping the reader's place, while emails
// filed through the API make the server write lines of its own (LogicalDOC logs each listener a filed file goes through,
// at info, naming the document by its id, which the email's copies give), stops when paused and catches up on Follow;
// its Download is a zip. The Errors screen loads with each window and filter; Background Jobs lists Healdan's jobs and
// LogicalDOC's tasks, and Schedule… opens on one and is cancelled; matter 1.1's and webuser's Activity tabs list their
// rows, webuser's with the view. It changes nothing global: no fault is resolved or reopened and no job is run, stopped,
// switched or rescheduled (the Dashboard's counts and the Indexer's pace are other steps' and specs'), webuser's account
// and matter 1.1 are only read, and its documents are in a folder of its own inside the shared E2E folder. Nothing it asks
// of the server is logged as an error, which the Errors screen would count. Each browser runs it, so its names carry the
// project's.
test.describe.configure({ mode: 'serial' })

const ADMIN = { username: ADMIN_USERNAME, password: ADMIN_PASSWORD }

/** This run's names, made in beforeAll */
let run = ''
let names = { group: '', renamed: '', folder: '', viewed: '', selected: '' }
/** The doc #s of the PDF webuser reads and of the one they only select */
let viewed = 0
let selected = 0
let folderId = 0

let api: APIRequestContext | undefined
let token = ''
let adminContext: BrowserContext | undefined
let admin: Page
let userContext: BrowserContext | undefined
let user: Page

const main = (page: Page) => page.getByRole('main')
const pane = (page: Page) => page.getByRole('navigation', { name: 'Navigation' })
const toast = (page: Page, text: string) => expect(page.getByText(text, { exact: true }).first()).toBeVisible()
/** A left pane entry, whose name may go on to its count, as "Errors, 2 need attention" */
const entry = (page: Page, name: string) => pane(page).getByRole('link', { name: new RegExp(`^${escaped(name)}(,|$)`) })
/** The rows of a list (the app's Table), without its headings */
const rowsOf = (list: Locator) => list.getByRole('row').filter({ has: list.page().getByRole('rowheader') })
/** Those of the rows with a cell that says exactly `text` */
const saying = (rows: Locator, text: string) => rows.filter({ has: rows.page().getByRole('gridcell', { name: text, exact: true }) })
/** Those of the rows whose Detail names the doc #, as "# 12, Viewed ….pdf, version 1.0" */
const naming = (rows: Locator, docNumber: number) => rows.filter({ hasText: new RegExp(`# ${docNumber}, `) })

/** Ticks a kind of the Audit Trail's or an Activity tab's: the box is hidden under its words, which a person clicks */
async function tick(within: Locator, kind: string) {
  const kinds = within.getByRole('group', { name: 'Kind' })
  await kinds.getByText(kind, { exact: true }).click()
  await expect(kinds.getByRole('checkbox', { name: kind })).toBeChecked()
}

/**
 * An email of this run's own, filed through the API as webuser into the run's folder, as a source of the server's log
 * lines; its LogicalDOC id, which those lines name it by, as the address of its copy gives it (`frontend.jsp?docId=`)
 */
async function fileEmail(name: string): Promise<number> {
  const messageId = `${name}@e2e.invalid`
  const lines = [
    'From: E2E <e2e@e2e.invalid>',
    'To: E2E <e2e@e2e.invalid>',
    `Subject: ${name}`,
    'Date: Sun, 22 Nov 2026 10:00:00 +0000',
    `Message-ID: <${messageId}>`,
    'MIME-Version: 1.0',
    'Content-Type: text/plain; charset="utf-8"',
    '',
    `The text of ${name}`,
    '',
  ]
  await fileDocument(api!, token, folderId, `${name}.eml`, 'message/rfc822', Buffer.from(lines.join('\r\n')))
  const found = await call<{ items: { copies: { address: string }[] }[] }>(
    api!.get(`/api/v1/emails?messageId=${encodeURIComponent(messageId)}`, authorized(token)),
    `the copies of ${name}`,
  )
  const id = /[?&]docId=(\d+)/.exec(found.items[0]?.copies[0]?.address ?? '')?.[1]
  expect(id, `the id of ${name}`).toBeDefined()
  return Number(id)
}

test.beforeAll(async ({ browser, playwright }, testInfo) => {
  expect(PASSWORD, 'WEBUSER_PASSWORD is set by docker/tests/test-web.sh').not.toBe('')
  expect(ADMIN_PASSWORD, 'ADMIN_PASSWORD is set by docker/tests/test-web.sh').not.toBe('')
  // A time and a random part, so two runs, or the two browsers, never share a name; letters first, so the Audit Trail's
  // box takes it as words, never as a doc #
  run = `mon-${testInfo.project.name}-${Date.now().toString(36)}${Math.floor(Math.random() * 36 ** 4).toString(36)}`
  names = {
    // Long, so that its rename's Detail runs well past the edge of the Audit Trail's list
    group: `E2E Monitoring Group ${run}, with a name long enough to run past the edge of a list`,
    renamed: `E2E Monitoring Group ${run}, renamed, with a name long enough to run past the edge of a list`,
    folder: `Monitoring ${run}`,
    viewed: `Viewed ${run}.pdf`,
    selected: `Selected ${run}.pdf`,
  }
  api = await apiContext(playwright.request, testInfo.project.use.baseURL)
  token = await deviceToken(api, run)
  const { documents } = await matterFolders(api, token)
  const e2e = await specsFolder(api, token, documents)
  const made = api.post('/api/v1/folders', { ...authorized(token), data: { parentId: e2e, name: names.folder } })
  folderId = (await call<{ id: number }>(made, 'making the folder')).id
  viewed = await fileDocument(api, token, folderId, names.viewed, 'application/pdf', minimalPdf(`Viewed ${run}`))
  selected = await fileDocument(api, token, folderId, names.selected, 'application/pdf', minimalPdf(`Selected ${run}`))
  adminContext = await browser.newContext(testInfo.project.use)
  admin = await adminContext.newPage()
  userContext = await browser.newContext(testInfo.project.use)
  user = await userContext.newPage()
})

test.afterAll(async () => {
  await userContext?.close()
  await adminContext?.close()
  if (!api) return
  if (token) await api.delete('/api/v1/auth/tokens/current', authorized(token))
  await api.dispose()
})

/**
 * The Audit Trail, at its address with no filter, once its first rows have come. The box takes a doc # or words; Kind
 * ticks the kinds kept.
 */
async function openAuditTrail() {
  await entry(admin, 'Audit Trail').click()
  await expect(admin).toHaveURL(/\/admin\/audit$/)
  await expect(main(admin).getByRole('heading', { level: 1, name: 'Audit Trail' })).toBeVisible()
  await expect(auditList().getByRole('row').nth(1)).toBeVisible()
}

const auditList = () => main(admin).getByRole('grid', { name: 'Audit Trail' })
const auditBox = () => main(admin).getByRole('group', { name: 'Filters' }).getByRole('textbox', { name: 'Doc #, Name or Address' })

/** The Audit Trail kept to one doc #'s rows, read to the end: the doc # read through its index, so one request finds them all */
async function auditOfDocument(docNumber: number) {
  await auditBox().fill(String(docNumber))
  await expect(admin).toHaveURL(new RegExp(`[?&]doc=${docNumber}(&|$)`))
  // Its filing's row, then the end of the list, with nothing more on its way
  await expect(saying(naming(rowsOf(auditList()), docNumber), 'Filed')).toHaveCount(1)
  await expect(main(admin).getByText('No older rows.')).toBeVisible()
  await expect(main(admin).locator('[aria-busy="true"]')).toHaveCount(0)
}

test('an administrative change made in another tab, found in the Audit Trail by Refresh with its old and new value, and exported', async () => {
  await admin.goto('/admin/audit')
  await expect(admin).toHaveURL(/\/login\.jsp/)
  await signIn(admin, ADMIN)
  await expect(admin).toHaveURL(/\/admin\/audit$/)
  await expect(main(admin).getByRole('heading', { level: 1, name: 'Audit Trail' })).toBeVisible()

  // The administration record's rows whose target's name holds this run's words, of which there's none yet. The page is
  // marked, so a reload, which would take the mark away, can't pass for Refresh
  await tick(main(admin), 'Administration')
  await auditBox().fill(run)
  await expect(admin).toHaveURL(new RegExp(`[?&]q=${escaped(run)}(&|$)`))
  await expect(admin).toHaveURL(/[?&]kind=administration(&|$)/)
  await expect(main(admin).getByText(/^(No rows match the filters\.|Nothing found back to )/)).toBeVisible()
  await admin.evaluate(() => void ((window as unknown as { kept?: boolean }).kept = true))

  await test.step("the run's own group, made and renamed in another tab", async () => {
    const other = await adminContext!.newPage()
    try {
      await other.goto('/admin/groups')
      await expect(other).toHaveURL(/\/admin\/groups$/)
      await main(other).getByRole('button', { name: 'New Group' }).click()
      const dialog = other.getByRole('dialog', { name: 'New Group' })
      await dialog.getByRole('textbox', { name: 'Name' }).fill(names.group)
      const create = dialog.getByRole('button', { name: 'Create Group' })
      // Once the name's look-up has found no group with it
      await expect(create).toBeEnabled()
      await create.click()
      await expect(main(other).getByRole('heading', { level: 1, name: names.group })).toBeVisible()

      await main(other).getByRole('button', { name: 'Rename', exact: true }).click()
      const rename = other.getByRole('dialog', { name: `Rename ${names.group}` })
      await rename.getByRole('textbox', { name: 'Name' }).fill(names.renamed)
      const button = rename.getByRole('button', { name: 'Rename', exact: true })
      await expect(button).toBeEnabled()
      await button.click()
      await toast(other, `Renamed ${names.group} to ${names.renamed}`)
      await expect(main(other).getByRole('heading', { level: 1, name: names.renamed })).toBeVisible()
    } finally {
      await other.close()
    }
  })

  const changed = saying(rowsOf(auditList()), 'Changed a group')
  await test.step('Refresh asks again, and the rows come with no reload', async () => {
    // The list is as it was read, until it's asked again
    await expect(changed).toHaveCount(0)
    const refreshed = admin.waitForResponse((response) => new URL(response.url()).pathname === '/api/v1/admin/audit' && response.request().method() === 'GET')
    await main(admin).getByRole('button', { name: 'Refresh', exact: true }).click()
    expect((await refreshed).status(), "Refresh's answer").toBe(200)
    await expect(changed).toHaveCount(1)
    await expect(saying(rowsOf(auditList()), 'Created a group')).toHaveCount(1)
    expect(await admin.evaluate(() => (window as unknown as { kept?: boolean }).kept), 'the page, not reloaded').toBe(true)
  })

  await expect(changed.getByRole('gridcell', { name: 'Admin Admin', exact: true })).toBeVisible()
  await expect(changed.getByRole('gridcell', { name: 'Administration', exact: true })).toBeVisible()
  // The Detail: the group as it's named now, then its one changed field, old and new, all on the row's one line
  await expect(changed).toContainText(`${names.renamed} · name: ${names.group} → ${names.renamed}`)
  // Every row keeps its height, the long Detail cut at the cell's edge rather than wrapped
  const heights = await auditList()
    .locator('[role="row"]:has([role="rowheader"])')
    .evaluateAll((rows) => rows.map((row) => Math.round(row.getBoundingClientRect().height)))
  expect(new Set(heights)).toEqual(new Set([34]))

  // Its details, under the list: the whole of it, with the change on a line of its own
  await changed.getByRole('rowheader').click()
  const details = main(admin).getByRole('region', { name: 'Row Details' })
  await expect(details).toBeVisible()
  const fact = (term: string) => details.locator('dt').filter({ hasText: new RegExp(`^${escaped(term)}$`) }).locator('xpath=following-sibling::dd[1]')
  await expect(fact('Who')).toHaveText('Admin Admin')
  await expect(fact('Kind')).toHaveText('Administration')
  await expect(fact('What')).toHaveText('Changed a group')
  await expect(fact('Detail')).toHaveText(names.renamed)
  await expect(details.getByRole('list', { name: 'Changes' }).getByRole('listitem')).toHaveText([`name: ${names.group} → ${names.renamed}`])

  await test.step('Export CSV is the rows the filters keep, as the server answered it', async () => {
    // The server's answer is the file (200, a CSV), not a refusal saved under the file's name
    const download = await downloadBy(admin, () => main(admin).getByRole('button', { name: 'Export CSV' }).click(), { type: /^text\/csv(;|$)/ })
    expect(download.suggestedFilename()).toMatch(/^audit-trail-\d{4}-\d{2}-\d{2}\.csv$/)
    const csv = readFileSync(await download.path(), 'utf-8')
    expect(csv).toContain('Changed a group')
    expect(csv).toContain('Created a group')
    expect(csv).toContain(names.renamed)
    // Administration rows only, as the filters keep
    expect(csv).not.toContain('Signed in')
  })
})

test('a PDF webuser reads in Quick Look is recorded as one view, however often, and one only selected as none', async () => {
  // Every preview webuser's browser asks for, by doc #
  const previews: number[] = []
  user.on('request', (request: Request) => {
    const asked = /\/api\/v1\/documents\/(\d+)\/preview$/.exec(new URL(request.url()).pathname)
    if (asked) previews.push(Number(asked[1]))
  })
  const answered = () =>
    user.waitForResponse((response) => new URL(response.url()).pathname === `/api/v1/documents/${viewed}/preview` && response.request().method() === 'GET')
  const row = (name: string) => main(user).getByRole('row', { name: new RegExp(escaped(name)) })

  await user.goto('/login.jsp')
  await signIn(user)
  await expect(user.getByRole('heading', { name: 'Recent Documents' })).toBeVisible()
  await user.goto(`/app/folders/${folderId}`)

  await test.step('one PDF only selected: its details, and no preview', async () => {
    await row(names.selected).getByRole('rowheader').click()
    await expect(user.getByRole('region', { name: 'Details' })).toContainText(names.selected)
  })

  await test.step('the other read in Quick Look, twice: once, and again after a reload', async () => {
    for (const time of ['first', 'second']) {
      await row(names.viewed).getByRole('rowheader').click()
      const preview = answered()
      await user.keyboard.press('Space')
      const dialog = user.getByRole('dialog', { name: `Quick Look: ${names.viewed}` })
      await expect(dialog).toBeVisible()
      // The server answered with the PDF itself, which it records as read; every answer of the API's is no-store, so
      // the second time is the server's too
      expect((await preview).status(), `the ${time} preview's answer`).toBe(200)
      await expect(dialog.getByText('Page 1 of 1')).toBeVisible()
      await user.keyboard.press('Escape')
      await expect(dialog).toBeHidden()
      if (time === 'first') await user.reload()
    }
  })
  expect(previews, 'the previews asked for').toEqual([viewed, viewed])

  await openAuditTrail()
  await test.step('the PDF read: one view, by webuser', async () => {
    await auditOfDocument(viewed)
    const views = saying(naming(rowsOf(auditList()), viewed), 'Viewed')
    await expect(views).toHaveCount(1)
    await expect(views.getByRole('gridcell', { name: 'User Web', exact: true })).toBeVisible()
    await expect(views.getByRole('gridcell', { name: 'Document', exact: true })).toBeVisible()
    await expect(views).toContainText(`# ${viewed}, ${names.viewed}, version 1.0`)
  })
  await test.step('the PDF only selected: no view', async () => {
    await auditOfDocument(selected)
    await expect(saying(rowsOf(auditList()), 'Viewed')).toHaveCount(0)
  })
})

test("the Server Log follows the main log, keeps the reader's place, stops when paused and catches up on Follow", async () => {
  // Three filings and two waits for the follow
  test.setTimeout(150_000)
  await entry(admin, 'Server Log').click()
  await expect(admin).toHaveURL(/\/admin\/server-log$/)
  await expect(main(admin).getByRole('combobox', { name: 'Log' })).toHaveValue('DMS')
  const box = main(admin).getByRole('region', { name: 'Lines of Main Log' })
  const entries = box.locator('li[data-position]')
  await expect(entries.first()).toBeVisible()
  await expect(main(admin).getByRole('status').filter({ hasText: 'Following Live' })).toBeVisible()

  /** How far the box is from its end, in pixels */
  const fromEnd = () => box.evaluate((element) => element.scrollHeight - element.scrollTop - element.clientHeight)
  /** The positions of the entries the box holds, oldest first */
  const positions = () => entries.evaluateAll((items) => items.map((item) => item.getAttribute('data-position') ?? ''))
  /**
   * Whether an entry after the one at `position` says the document with LogicalDOC's id `id` went through a listener as
   * it was filed: what filing it makes the server write, `Before listener <class>: document=<id>, recordVersion=0`
   */
  const loggedAfter = (position: string, id: number) =>
    entries.evaluateAll(
      (items, [after, docId]) => {
        const at = items.findIndex((item) => item.getAttribute('data-position') === after)
        const line = new RegExp(`Before listener [\\w.$]+: document=${docId},`)
        return at >= 0 && items.slice(at + 1).some((item) => line.test(item.textContent ?? ''))
      },
      [position, id] as const,
    )
  const newest = async () => (await positions()).at(-1) ?? ''

  await test.step('at its end, a line the server writes after the page opened comes, and the box stays at its end', async () => {
    await expect.poll(fromEnd).toBeLessThanOrEqual(4)
    const last = await newest()
    const id = await fileEmail(`logged-${run}-1`)
    await expect.poll(() => loggedAfter(last, id), { message: 'a line of the filing, after the newest when the page opened' }).toBe(true)
    await expect.poll(fromEnd).toBeLessThanOrEqual(4)
  })

  await test.step('read further up, the entry at the top stays where it is as lines come', async () => {
    // Halfway up: not at its end, nor at its top, which would read earlier lines
    await box.evaluate((element) => {
      element.scrollTop = Math.floor((element.scrollHeight - element.clientHeight) / 2)
    })
    await expect.poll(fromEnd).toBeGreaterThan(40)
    /** The first entry in view at the box's top, and how far below the top it starts */
    const top = () =>
      box.evaluate((element) => {
        const edge = element.getBoundingClientRect().top
        const item = [...element.querySelectorAll('li[data-position]')].find((one) => one.getBoundingClientRect().bottom > edge)
        return item ? { position: item.getAttribute('data-position'), offset: Math.round(item.getBoundingClientRect().top - edge) } : null
      })
    const before = await top()
    expect(before, 'an entry in view').not.toBeNull()
    const last = await newest()
    const id = await fileEmail(`logged-${run}-2`)
    await expect.poll(() => loggedAfter(last, id), { message: 'a line of the filing, while read further up' }).toBe(true)
    const after = await box.evaluate((element, position) => {
      const item = [...element.querySelectorAll('li[data-position]')].find((one) => one.getAttribute('data-position') === position)
      return item ? Math.round(item.getBoundingClientRect().top - element.getBoundingClientRect().top) : null
    }, before!.position)
    expect(after, 'the entry that was at the top, where it was').not.toBeNull()
    expect(Math.abs(after! - before!.offset), `the entry at the top moved from ${before!.offset} to ${after} pixels`).toBeLessThanOrEqual(1)
    expect(await fromEnd(), 'not taken to the end').toBeGreaterThan(40)
    // Back to the end for the rest
    await box.evaluate((element) => {
      element.scrollTop = element.scrollHeight
    })
    await expect.poll(fromEnd).toBeLessThanOrEqual(4)
  })

  await test.step('paused, it reads nothing new; Follow reads what was written meanwhile', async () => {
    // The page's own reads of what's new, which carry where the last one ended
    const follows: string[] = []
    const note = (request: Request) => {
      const url = new URL(request.url())
      if (url.pathname === '/api/v1/admin/logs/DMS/lines' && url.searchParams.has('after')) follows.push(url.search)
    }
    await main(admin).getByRole('button', { name: 'Pause', exact: true }).click()
    await expect(main(admin).getByRole('status').filter({ hasText: 'Paused' })).toBeVisible()
    await expect(main(admin).getByRole('button', { name: 'Follow', exact: true })).toBeVisible()
    admin.on('request', note)
    try {
      const last = await newest()
      const id = await fileEmail(`logged-${run}-3`)
      // The server has written them: its newest lines, as a read of the log's end gives them, name the email
      await expect
        .poll(async () => {
          const answer = await admin.evaluate(() =>
            fetch('/api/v1/admin/logs/DMS/lines').then((response) => response.json() as Promise<{ lines: { message: string }[] }>),
          )
          return answer.lines.some((line) => line.message.startsWith('Before listener') && line.message.includes(`document=${id},`))
        }, { message: "the server has the filing's lines" })
        .toBe(true)
      // Two of the follow's turns (every 3 seconds) go by with no read and no new line: what can't come can only be waited for
      await admin.waitForTimeout(7_000)
      expect(follows, "no read of what's new while paused").toEqual([])
      expect(await loggedAfter(last, id), 'no line of the filing while paused').toBe(false)

      await main(admin).getByRole('button', { name: 'Follow', exact: true }).click()
      await expect(main(admin).getByRole('status').filter({ hasText: 'Following Live' })).toBeVisible()
      await expect.poll(() => loggedAfter(last, id), { message: 'the line written while paused, once following again' }).toBe(true)
      expect(follows.length, "Follow read what's new").toBeGreaterThan(0)
      await expect.poll(fromEnd).toBeLessThanOrEqual(4)
    } finally {
      admin.off('request', note)
    }
  })

  await test.step("Download is the log's files, zipped, as the server answered it", async () => {
    // The server's answer is the file (200, a zip, after which the servlet container may name a charset, which a zip's
    // bytes ignore), not a refusal saved under the file's name
    const press = () => main(admin).getByRole('button', { name: 'Download', exact: true }).click()
    const download = await downloadBy(admin, press, { type: /^application\/zip(;|$)/ })
    expect(new URL(download.url()).pathname).toBe('/api/v1/admin/logs/DMS/file')
    expect(download.suggestedFilename()).toMatch(/\.zip$/)
  })

  await test.step('Show Earlier, pressed until the start of a log, gives its focus to the lines', async () => {
    // A log the first read doesn't reach the start of, and some presses do: of 200 to 3,000 entries, as a read gives 200.
    // Which log that is on a stack depends on how long it has run (the Indexer's grows every 5 seconds in the browser
    // tests), so each is tried, the main log last, and the test says which, or that none was; the logs are only read
    const menu = main(admin).getByRole('combobox', { name: 'Log' })
    const logs = await menu
      .locator('option')
      .evaluateAll((options) => options.map((option) => ({ name: (option as HTMLOptionElement).value, label: option.textContent ?? '' })))
    const tried = [...logs.filter((log) => log.name !== 'DMS'), ...logs.filter((log) => log.name === 'DMS')]
    let reached = ''
    for (const log of tried) {
      await menu.selectOption(log.name)
      const lines = main(admin).getByRole('region', { name: `Lines of ${log.label}` })
      const start = lines.getByText('This is the start of the log: it holds nothing older.')
      const earlier = lines.getByRole('button', { name: 'Show Earlier' })
      await expect(start.or(earlier)).toBeVisible()
      if (await start.isVisible()) continue
      // Pressed by the keyboard, as a person who doesn't point would, the focus staying on the button while there's more,
      // fourteen times at most. Each read puts older entries first, past the 1,000 the box keeps too, when it drops the
      // newest instead, so the oldest entry it holds says a read has come
      const oldest = () => lines.locator('li[data-position]').first().getAttribute('data-position')
      for (let press = 0; press < 14 && !(await start.isVisible()); press++) {
        const before = await oldest()
        await earlier.focus()
        await admin.keyboard.press('Enter')
        await expect
          .poll(async () => (await start.isVisible()) || (await oldest()) !== before, { message: `Show Earlier's read of ${log.label}` })
          .toBe(true)
      }
      if (!(await start.isVisible())) continue
      // The button went with the start of the log, and its focus to the box of lines
      await expect(lines).toBeFocused()
      reached = log.label
      break
    }
    const note = reached
      ? `Show Earlier reached the start of ${reached}`
      : `No log reached its start within fourteen presses of Show Earlier: ${tried.map((log) => log.label).join(', ')}`
    // Said in the run's output too, as which log it was, if any, is the stack's
    test.info().annotations.push({ type: 'note', description: note })
    console.log(`admin-monitoring (${test.info().project.name}): ${note}`)
    await menu.selectOption('DMS')
  })
})

test("Errors opens, and each window and filter is answered; a fault's details, when there is one, without resolving it", async () => {
  const list = main(admin).getByRole('grid', { name: 'Errors' })
  const filters = main(admin).getByRole('group', { name: 'Error Filters' })
  const faults = list.getByRole('row').filter({ has: admin.getByRole('rowheader') })
  /** The list's next answer whose parameters include `wanted` */
  const asked = (wanted: Record<string, string>) =>
    admin.waitForResponse((response) => {
      const url = new URL(response.url())
      return url.pathname === '/api/v1/admin/errors' && Object.entries(wanted).every(([name, value]) => url.searchParams.get(name) === value)
    })
  /** The list drawn from an answer: its rows, or what it says when it has none, and no failure */
  const drawn = async () => {
    await expect(main(admin).getByText('Loading…')).toHaveCount(0)
    await expect(main(admin).getByRole('button', { name: 'Try Again' })).toHaveCount(0)
    await expect(faults.first().or(main(admin).getByText(/^No errors /))).toBeVisible()
  }
  // The first answer, of the last 24 hours, as the screen opens
  const first = asked({ window: '24h' })
  await entry(admin, 'Errors').click()
  await expect(admin).toHaveURL(/\/admin\/errors$/)
  await expect(main(admin).getByRole('heading', { level: 1, name: 'Errors' })).toBeVisible()
  expect((await first).status(), 'the 24 Hours window').toBe(200)
  await drawn()

  await test.step('each window', async () => {
    const windows = main(admin).getByRole('radiogroup', { name: 'How Far Back' })
    await expect(windows.getByRole('radio', { name: '24 Hours' })).toBeChecked()
    for (const [label, window] of [['1 Hour', '1h'], ['7 Days', '7d'], ['30 Days', '30d']]) {
      const answer = asked({ window })
      await windows.getByRole('radio', { name: label }).click()
      expect((await answer).status(), `the ${label} window`).toBe(200)
      await expect(windows.getByRole('radio', { name: label })).toBeChecked()
      await drawn()
    }
  })

  await test.step('the area, Unresolved Only and the words', async () => {
    let answer = asked({ window: '30d', area: 'Email' })
    await filters.getByRole('combobox', { name: 'Area' }).selectOption({ label: 'Email' })
    expect((await answer).status(), 'the Email area').toBe(200)
    await drawn()
    answer = asked({ window: '30d', area: 'Email', unresolved: 'true' })
    await filters.getByRole('checkbox', { name: 'Unresolved Only' }).check()
    expect((await answer).status(), 'Unresolved Only').toBe(200)
    await drawn()
    // Words no fault holds: the list says none match
    const words = `${run}-none`
    answer = asked({ window: '30d', q: words })
    await filters.getByRole('textbox', { name: 'Message or Logger' }).fill(words)
    expect((await answer).status(), 'the words').toBe(200)
    await expect(main(admin).getByText('No errors match these filters.')).toBeVisible()
    await filters.getByRole('button', { name: 'Clear Filters' }).click()
    await expect(admin).toHaveURL(/\/admin\/errors\?window=30d$/)
    // The button goes with the filters, and its focus to the words' field
    await expect(filters.getByRole('textbox', { name: 'Message or Logger' })).toBeFocused()
    await drawn()
  })

  // drawn() has waited for the rows or for what the list says without any
  if ((await faults.count()) === 0) {
    test.info().annotations.push({ type: 'note', description: 'No fault in the last 30 days, so no details were opened' })
    return
  }
  await test.step("the first fault's details, below the list, with nothing pressed", async () => {
    await faults.first().getByRole('rowheader').click()
    await expect(admin).toHaveURL(/[?&]id=\d+/)
    const details = main(admin).getByRole('region', { name: 'Error Details' })
    await expect(details.getByRole('group', { name: 'Latest Message' })).not.toBeEmpty()
    await expect(details.getByRole('button', { name: 'Copy Details' })).toBeVisible()
    await expect(details.getByRole('button', { name: /^(Mark Resolved|Reopen)$/ })).toBeVisible()
  })

  await test.step('the list and the details share the page at 1,280 × 800 and at 768 pixels', async () => {
    for (const size of [{ width: 1280, height: 800 }, { width: 768, height: 1024 }]) {
      await admin.setViewportSize(size)
      const details = main(admin).getByRole('region', { name: 'Error Details' })
      await expect(details).toBeVisible()
      await expect
        .poll(async () => {
          const [rows, below] = await Promise.all([list.boundingBox(), details.boundingBox()])
          if (rows === null || below === null) return 'not drawn'
          // Rows enough to choose from, the details under them with room of their own, and both in the window
          if (rows.height < 150) return `the list is ${rows.height} pixels high`
          if (below.height < 100) return `the details are ${below.height} pixels high`
          if (below.y < rows.y + rows.height - 1) return 'the details overlap the list'
          if (below.y + below.height > size.height + 1) return 'the details run off the window'
          return 'shared'
        }, { message: `at ${size.width} × ${size.height}` })
        .toBe('shared')
      expect(await admin.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth), 'nothing scrolls sideways').toBe(true)
    }
    await admin.setViewportSize({ width: 1280, height: 720 })
  })
})

test("Background Jobs lists Healdan's jobs and LogicalDOC's tasks; Schedule… opens on a task and is cancelled", async () => {
  // It only reads: no request it makes may change anything
  const changes: string[] = []
  const writes = (request: Request) => {
    if (!['GET', 'HEAD', 'OPTIONS'].includes(request.method())) changes.push(`${request.method()} ${new URL(request.url()).pathname}`)
  }
  admin.on('request', writes)
  try {
    await entry(admin, 'Background Jobs').click()
    await expect(admin).toHaveURL(/\/admin\/jobs$/)
    const table = main(admin).getByRole('table', { name: 'Background Jobs' })
    await expect(table.getByRole('rowheader', { name: "Healdan's Jobs" })).toBeVisible()
    await expect(table.getByRole('rowheader', { name: "LogicalDOC's Tasks" })).toBeVisible()
    for (const job of ['Previews', 'Email Index', 'Trash Destroyer', 'Storage Mover', 'Indexer', 'Tags Processor']) {
      await expect(table.getByRole('rowheader', { name: job, exact: true })).toBeVisible()
    }

    // Tags Processor's schedule, whatever it is, as its row says it (the cells after its name: Last Run, Result, Next Run,
    // Enabled, then Schedule), opened on what it has, then cancelled
    const tags = table.getByRole('row').filter({ has: admin.getByRole('rowheader', { name: 'Tags Processor', exact: true }) })
    const said = (await tags.getByRole('cell').nth(4).innerText()).trim()
    const every = /^Every (?:([\d,]+) )?(second|minute|hour)s?$/.exec(said)
    expect(every, `Tags Processor's schedule, "${said}", every so many seconds, minutes or hours`).not.toBeNull()
    const schedule = table.getByRole('button', { name: 'Schedule of Tags Processor' })
    await schedule.click()
    const dialog = admin.getByRole('dialog', { name: 'Schedule of Tags Processor' })
    await expect(dialog.getByRole('radio', { name: 'Every So Often' })).toBeChecked()
    await expect(dialog.getByRole('textbox', { name: 'Every' })).toHaveValue((every![1] ?? '1').replace(/,/g, ''))
    await expect(dialog.getByRole('combobox', { name: 'Unit' })).toHaveValue(`${every![2]}s`)
    await dialog.getByRole('button', { name: 'Cancel' }).click()
    await expect(dialog).toBeHidden()
    // The focus back on the button that opened it, and the schedule as it was
    await expect(schedule).toBeFocused()
    await expect(tags.getByRole('cell').nth(4)).toHaveText(said)
  } finally {
    admin.off('request', writes)
  }
  expect(changes, 'Background Jobs, only read').toEqual([])
})

test("matter 1.1's Activity lists its rows, and webuser's has the view", async () => {
  await entry(admin, 'Matters').click()
  await main(admin).getByRole('grid', { name: 'Matters' }).getByRole('row', { name: /^1\.1 Smoke Matter/ }).click()
  await expect(main(admin).getByRole('heading', { level: 1, name: '1.1 Smoke Matter' })).toBeVisible()
  await main(admin).getByRole('tab', { name: 'Activity' }).click()
  await expect(admin).toHaveURL(/[?&]tab=activity(&|$)/)
  const matterActivity = main(admin).getByRole('grid', { name: 'Activity of 1.1 Smoke Matter' })
  await expect(matterActivity.getByRole('row').nth(1)).toBeVisible()
  await expect(main(admin).getByRole('button', { name: 'Try Again' })).toHaveCount(0)
  await expect(main(admin).getByRole('link', { name: 'Open in the Audit Trail' })).toHaveAttribute('href', /^\/admin\/audit\?matter=\d+$/)

  // webuser's page, which this only reads
  await entry(admin, 'Users').click()
  await main(admin).getByRole('group', { name: 'Filter Users' }).getByRole('textbox', { name: 'Name, Username or Email' }).fill('webuser')
  const users = main(admin).getByRole('grid', { name: 'Users' })
  await users.getByRole('row').filter({ has: admin.getByRole('gridcell', { name: 'webuser', exact: true }) }).getByRole('rowheader').click()
  await expect(main(admin).getByRole('heading', { level: 1, name: 'User Web' })).toBeVisible()
  // The Overview first, as the page opens
  await expect(main(admin).getByRole('tab', { name: 'Overview' })).toHaveAttribute('aria-selected', 'true')
  await expect(main(admin).getByRole('region', { name: 'Details' })).toBeVisible()
  await main(admin).getByRole('tab', { name: 'Activity' }).click()
  const panel = main(admin).getByRole('tabpanel', { name: 'Activity' })
  await tick(panel, 'Document')
  // Its own days and Refresh, beside the way to the whole Audit Trail
  await expect(panel.getByRole('group', { name: 'From Date' })).toBeVisible()
  await expect(panel.getByRole('group', { name: 'To Date' })).toBeVisible()
  await expect(panel.getByRole('button', { name: 'Refresh', exact: true })).toBeVisible()
  // The newest of webuser's document rows: the view is among them
  const personActivity = main(admin).getByRole('grid', { name: 'Activity of User Web' })
  const views = saying(naming(rowsOf(personActivity), viewed), 'Viewed')
  await expect(views).toHaveCount(1)
  await expect(views).toContainText(names.viewed)

  await test.step("the same, read to their end in the Audit Trail: webuser's document rows of each PDF", async () => {
    const whole = panel.getByRole('link', { name: 'Open in the Audit Trail' })
    await expect(whole).toHaveAttribute('href', /^\/admin\/audit\?(?=.*\bkind=document\b)(?=.*\buser=\d+\b)/)
    await whole.click()
    await expect(admin).toHaveURL(/\/admin\/audit\?/)
    await expect(main(admin).getByRole('group', { name: 'Filters' }).getByRole('combobox', { name: 'Person' })).toHaveValue(/User Web/)
    // Each doc # read through its index, so one request reads its rows to their end: the view of the one read, by webuser,
    // and none of the one only selected
    await auditOfDocument(viewed)
    await expect(saying(naming(rowsOf(auditList()), viewed), 'Viewed')).toHaveCount(1)
    await auditOfDocument(selected)
    await expect(saying(rowsOf(auditList()), 'Viewed')).toHaveCount(0)
  })
})
