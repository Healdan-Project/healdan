// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { expect, test, type APIRequestContext, type BrowserContext, type Locator, type Page, type Request } from '@playwright/test'
import {
  ADMIN_PASSWORD,
  ADMIN_USERNAME,
  PASSWORD,
  apiContext,
  authorized,
  call,
  deviceToken,
  escaped,
  fileDocument,
  matterFolders,
  minimalPdf,
  signIn,
  specsFolder,
} from './helpers'

// Healdan Admin's Phase 5 end to end (admin spec 8.8 and 17.1), as the stack's admin. A group of this run's own is made and
// renamed, and the Audit Trail finds the rename with the old and the new name, in its row and its details. webuser, in a
// browser of their own, selects one PDF of this run's own and reads another in Quick Look, twice: the Audit Trail then has
// one Viewed row for the one read and none for the one only selected. The Server Log follows the main log, keeping the
// reader's place, while documents filed through the API make the server write lines of its own (LogicalDOC logs each
// listener a filed file goes through, at info), stops when paused and catches up on Follow; its Download answers a zip.
// The Errors screen loads with each window and filter; Background Jobs lists Healdan's jobs and LogicalDOC's tasks, and
// Schedule… opens on one and is cancelled; matter 1.1's and webuser's Activity tabs list their rows, webuser's with the
// view. It changes nothing global: no fault is resolved or reopened and no job is run, stopped, switched or rescheduled
// (the Dashboard's counts and the Indexer's pace are other steps' and specs'), webuser's account and matter 1.1 are only
// read, and its documents are in a folder of its own inside the shared E2E folder. Nothing it asks of the server is logged
// as an error, which the Errors screen would count. Each browser runs it, so its names carry the project's.
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

/** A document of this run's own, filed through the API as webuser into the run's folder, as a source of the server's log lines */
const fileText = (name: string) => fileDocument(api!, token, folderId, name, 'text/plain', Buffer.from(`The text of ${name}`))

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

test('an administrative change of its own, found in the Audit Trail with the old and the new value', async () => {
  await admin.goto('/admin/groups')
  await expect(admin).toHaveURL(/\/login\.jsp/)
  await signIn(admin, ADMIN)
  await expect(admin).toHaveURL(/\/admin\/groups$/)

  await test.step("the run's own group, made and renamed", async () => {
    await main(admin).getByRole('button', { name: 'New Group' }).click()
    const dialog = admin.getByRole('dialog', { name: 'New Group' })
    await dialog.getByRole('textbox', { name: 'Name' }).fill(names.group)
    const create = dialog.getByRole('button', { name: 'Create Group' })
    // Once the name's look-up has found no group with it
    await expect(create).toBeEnabled()
    await create.click()
    await expect(main(admin).getByRole('heading', { level: 1, name: names.group })).toBeVisible()

    await main(admin).getByRole('button', { name: 'Rename', exact: true }).click()
    const rename = admin.getByRole('dialog', { name: `Rename ${names.group}` })
    await rename.getByRole('textbox', { name: 'Name' }).fill(names.renamed)
    const button = rename.getByRole('button', { name: 'Rename', exact: true })
    await expect(button).toBeEnabled()
    await button.click()
    await toast(admin, `Renamed ${names.group} to ${names.renamed}`)
    await expect(main(admin).getByRole('heading', { level: 1, name: names.renamed })).toBeVisible()
  })

  await openAuditTrail()
  // The administration record's rows, kept to those whose target's name holds this run's words: the group's create and rename
  await tick(main(admin), 'Administration')
  await auditBox().fill(run)
  await expect(admin).toHaveURL(new RegExp(`[?&]q=${escaped(run)}(&|$)`))
  await expect(admin).toHaveURL(/[?&]kind=administration(&|$)/)
  const changed = saying(rowsOf(auditList()), 'Changed a group')
  await expect(changed).toHaveCount(1)
  await expect(saying(rowsOf(auditList()), 'Created a group')).toHaveCount(1)
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
   * Whether an entry after the one at `position` says a filed file went through a listener: what a document filed after
   * the box held that entry makes the server write
   */
  const loggedAfter = (position: string) =>
    entries.evaluateAll(
      (items, after) => {
        const at = items.findIndex((item) => item.getAttribute('data-position') === after)
        return at >= 0 && items.slice(at + 1).some((item) => (item.textContent ?? '').includes('Before listener'))
      },
      position,
    )
  const newest = async () => (await positions()).at(-1) ?? ''

  await test.step('at its end, a line the server writes after the page opened comes, and the box stays at its end', async () => {
    await expect.poll(fromEnd).toBeLessThanOrEqual(4)
    const last = await newest()
    await fileText(`Logged ${run} 1.txt`)
    await expect.poll(() => loggedAfter(last), { message: 'a line of the filing, after the newest when the page opened' }).toBe(true)
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
    await fileText(`Logged ${run} 2.txt`)
    await expect.poll(() => loggedAfter(last), { message: 'a line of the filing, while read further up' }).toBe(true)
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
      const held = await positions()
      await fileText(`Logged ${run} 3.txt`)
      // The server has written it: its newest lines, as a read of the log's end gives them, hold one the box hasn't
      await expect
        .poll(async () => {
          const answer = await admin.evaluate(() =>
            fetch('/api/v1/admin/logs/DMS/lines').then((response) => response.json() as Promise<{ lines: { position: string; message: string }[] }>),
          )
          return answer.lines.some((line) => line.message.includes('Before listener') && !held.includes(line.position))
        }, { message: "the server has the filing's lines" })
        .toBe(true)
      // Two of the follow's turns (every 3 seconds) go by with no read and no new line: what can't come can only be waited for
      await admin.waitForTimeout(7_000)
      expect(follows, "no read of what's new while paused").toEqual([])
      expect(await loggedAfter(last), 'no new line while paused').toBe(false)

      await main(admin).getByRole('button', { name: 'Follow', exact: true }).click()
      await expect(main(admin).getByRole('status').filter({ hasText: 'Following Live' })).toBeVisible()
      await expect.poll(() => loggedAfter(last), { message: 'the line written while paused, once following again' }).toBe(true)
      expect(follows.length, "Follow read what's new").toBeGreaterThan(0)
      await expect.poll(fromEnd).toBeLessThanOrEqual(4)
    } finally {
      admin.off('request', note)
    }
  })

  await test.step("Download is the log's files, zipped", async () => {
    const link = main(admin).getByRole('link', { name: 'Download' })
    const href = await link.getAttribute('href')
    expect(href).toBe('/api/v1/admin/logs/DMS/file')
    // Its headers alone: the browser's own request, with the page's session
    const answer = await admin.evaluate(
      (url) => fetch(url, { method: 'HEAD' }).then((response) => ({ status: response.status, type: response.headers.get('content-type') })),
      href!,
    )
    expect(answer.status, "Download's answer").toBe(200)
    // The servlet container may name a charset after it, which a zip's bytes ignore
    expect(answer.type, "Download's type").toMatch(/^application\/zip(;|$)/)
  })
})

test("Errors opens, and each window and filter is answered; a fault's details, when there is one, without resolving it", async () => {
  await entry(admin, 'Errors').click()
  await expect(admin).toHaveURL(/\/admin\/errors$/)
  await expect(main(admin).getByRole('heading', { level: 1, name: 'Errors' })).toBeVisible()
  const list = main(admin).getByRole('grid', { name: 'Errors' })
  const filters = main(admin).getByRole('group', { name: 'Error Filters' })
  /** The list's next answer whose parameters include `with` */
  const asked = (wanted: Record<string, string>) =>
    admin.waitForResponse((response) => {
      const url = new URL(response.url())
      return url.pathname === '/api/v1/admin/errors' && Object.entries(wanted).every(([name, value]) => url.searchParams.get(name) === value)
    })
  /** The list drawn from an answer: its rows, or what it says when it has none, and no failure */
  const drawn = async () => {
    await expect(main(admin).getByText('Loading…')).toHaveCount(0)
    await expect(main(admin).getByRole('button', { name: 'Try Again' })).toHaveCount(0)
    await expect(list).toBeVisible()
  }
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
    await drawn()
  })

  const faults = list.getByRole('row').filter({ has: admin.getByRole('rowheader') })
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

    // Tags Processor's schedule, opened on what it has, then cancelled
    await table.getByRole('button', { name: 'Schedule of Tags Processor' }).click()
    const dialog = admin.getByRole('dialog', { name: 'Schedule of Tags Processor' })
    await expect(dialog.getByRole('radio', { name: 'Every So Often' })).toBeChecked()
    await expect(dialog.getByRole('textbox', { name: 'Every' })).toHaveValue('30')
    await expect(dialog.getByRole('combobox', { name: 'Unit' })).toHaveValue('minutes')
    await dialog.getByRole('button', { name: 'Cancel' }).click()
    await expect(dialog).toBeHidden()
    const tags = table.getByRole('row').filter({ has: admin.getByRole('rowheader', { name: 'Tags Processor', exact: true }) })
    await expect(tags).toContainText('Every 30 minutes')
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
  await tick(main(admin).getByRole('tabpanel', { name: 'Activity' }), 'Document')
  const personActivity = main(admin).getByRole('grid', { name: 'Activity of User Web' })
  const views = saying(naming(rowsOf(personActivity), viewed), 'Viewed')
  await expect(views).toHaveCount(1)
  await expect(views).toContainText(names.viewed)
  await expect(saying(naming(rowsOf(personActivity), selected), 'Viewed')).toHaveCount(0)
})
