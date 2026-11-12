// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { expect, test, type APIRequestContext, type Browser, type BrowserContext, type Locator, type Page } from '@playwright/test'
import { PASSWORD, authorized, call, deviceToken, fileDocument, matterFolders, minimalPdf, signIn, specsFolder, tabTo } from './helpers'

// The User app by keyboard alone (M6 spec section 15.2, rows 3 to 9; section 10.5): after the sign-in page, every step is a
// key (`page.keyboard`), and where a control is reached it is by Tab (`tabTo`). `focus()` only starts a step at a place the
// walkthrough reaches some other way (a row, which Tab and the arrow keys reach: row 4 proves it), and says so. No click. Where a
// step can't be done that way, that is a finding for the app, not something to work around here. The documents are this
// run's own, in a folder of its own inside E2E, a folder of matter 1.1's Documents folder (which the smoke fixture makes) that
// the specs which make folders share, so the list holds nothing else, whatever earlier runs left. One sign-in serves the whole file, and the tests run one after another.
const RUN = `key${Date.now().toString(36)}`
const NAMES = {
  alpha: `Alpha ${RUN}.txt`,
  bravo: `Bravo ${RUN}.txt`,
  charlie: `Charlie ${RUN}.txt`,
  pdf: `Quick Look ${RUN}.pdf`,
  rename: `Rename me ${RUN}.txt`,
  move: `Move me ${RUN}.txt`,
  remove: `Delete me ${RUN}.txt`,
}
const SUBJECT = `Keyboard email ${RUN}`
const ATTACHMENT = `Report ${RUN}.pdf`
// Hosts under .invalid never resolve: the picture is the email's tracker, which only Show Pictures loads
const TRACKER = 'https://tracker.invalid/pixel.gif'
const WORK = `Keys ${RUN}`
const TARGET = `Moved here ${RUN}`
const MADE = `Made ${RUN}`

function email(): Buffer {
  const pdf = minimalPdf(`Report ${RUN}`).toString('base64').replace(/(.{76})/g, '$1\r\n')
  return Buffer.from(
    [
      'From: Dunmere Pryce <dunmere@pryce.example>',
      'To: Priya Nair <priya@example.test>',
      `Subject: ${SUBJECT}`,
      'Date: Tue, 03 Nov 2026 10:00:00 +0000',
      `Message-ID: <keyboard-${RUN}@pryce.example>`,
      'MIME-Version: 1.0',
      'Content-Type: multipart/mixed; boundary="=_mixed"',
      '',
      '--=_mixed',
      'Content-Type: text/html; charset="utf-8"',
      '',
      `<html><body><p>Hello from the run ${RUN}</p><img src="${TRACKER}?run=${RUN}" width="40" height="40" alt="Tracking pixel"></body></html>`,
      '',
      '--=_mixed',
      'Content-Type: application/pdf',
      'Content-Transfer-Encoding: base64',
      `Content-Disposition: attachment; filename="${ATTACHMENT}"`,
      '',
      pdf,
      '',
      '--=_mixed--',
      '',
    ].join('\r\n'),
    'utf-8',
  )
}

test.describe.configure({ mode: 'serial' })

let api: APIRequestContext | undefined
let token = ''
let context: BrowserContext | undefined
let page: Page
let workFolder = 0
let matterFolder = 0
const numbers: Record<keyof typeof NAMES | 'email', number> = { alpha: 0, bravo: 0, charlie: 0, pdf: 0, rename: 0, move: 0, remove: 0, email: 0 }
const trackers: string[] = []

test.beforeAll(async ({ playwright, browser }, testInfo) => {
  expect(PASSWORD, 'WEBUSER_PASSWORD is set by docker/tests/test-web.sh').not.toBe('')
  api = await playwright.request.newContext({ baseURL: testInfo.project.use.baseURL })
  token = await deviceToken(api, RUN)
  const folders = await matterFolders(api, token)
  matterFolder = folders.matter
  const specs = await specsFolder(api, token, folders.documents)
  const makeFolder = async (parentId: number, name: string) =>
    (await call<{ id: number }>(api!.post('/api/v1/folders', { ...authorized(token), data: { parentId, name } }), `making ${name}`)).id
  workFolder = await makeFolder(specs, WORK)
  // The folder to move to is inside the work folder, so it adds no row to a list the other specs look at
  await makeFolder(workFolder, TARGET)
  const file = (name: string, mime: string, body: Buffer) => fileDocument(api!, token, workFolder, name, mime, body)
  for (const key of ['alpha', 'bravo', 'charlie', 'rename', 'move', 'remove'] as const) numbers[key] = await file(NAMES[key], 'text/plain', Buffer.from(`${key} ${RUN}`))
  numbers.pdf = await file(NAMES.pdf, 'application/pdf', minimalPdf(`Quick Look ${RUN}`))
  numbers.email = await file(`${SUBJECT}.eml`, 'message/rfc822', email())

  context = await newContext(browser, testInfo.project.use)
  await context.route(/\.invalid\//, (route) => {
    trackers.push(route.request().url())
    return route.fulfill({ status: 200, contentType: 'image/gif', body: Buffer.from('R0lGODlhAQABAAAAACw=', 'base64') })
  })
  page = await context.newPage()
  await page.goto('/login.jsp')
  await signIn(page)
  await expect(page.getByRole('heading', { name: 'Recent Documents' })).toBeVisible()
})

test.afterAll(async () => {
  await context?.close()
  if (!api) return
  if (token) await api.delete('/api/v1/auth/tokens/current', authorized(token))
  await api.dispose()
})

// The project's own settings (the browser's device, its base address) for a context of this file's own
function newContext(browser: Browser, use: Record<string, unknown>) {
  return browser.newContext({ ...use })
}

const main = () => page.getByRole('main')
const list = () => main().getByRole('grid')
const escape = (text: string) => new RegExp(text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'))
const row = (name: string) => list().getByRole('row', { name: escape(name) })
const inRow = (target: Locator) => target.evaluate((element) => element.contains(document.activeElement))

async function openFolder(sort = 'edited&order=desc') {
  await page.goto(`/app/folders/${workFolder}?sort=${sort}`)
  await expect(row(NAMES.alpha)).toBeVisible()
}

test('row 3: / jumps to the search box; a suggestion, a # and #n.N, a word and a matter number all search by keys; Filters narrow by a tick', async () => {
  await openFolder()
  const box = page.getByRole('combobox', { name: 'Search' })
  // Nothing in a field, so the slash is the shortcut's
  await page.keyboard.press('/')
  await expect(box).toBeFocused()

  // A suggestion, chosen with the arrow keys: a document, whose title the box offers as it is typed
  await page.keyboard.type(`Alpha ${RUN}`)
  await expect(page.getByRole('option', { name: escape(NAMES.alpha) })).toBeVisible({ timeout: 60_000 })
  await page.keyboard.press('ArrowDown')
  await page.keyboard.press('Enter')
  await expect(page).toHaveURL(new RegExp(`/app/doc/${numbers.alpha}$`))

  // The box keeps the focus after a search, so the slash is pressed only when it doesn't have it (a slash in a field is typed)
  const searchFor = async (text: string) => {
    if (!(await box.evaluate((element) => element === document.activeElement))) await page.keyboard.press('/')
    await expect(box).toBeFocused()
    await page.keyboard.press('Control+A')
    await page.keyboard.type(text)
    await page.keyboard.press('Enter')
  }

  // A # opens its document, by number and with a version
  await searchFor(`#${numbers.alpha}`)
  await expect(page).toHaveURL(new RegExp(`/app/doc/${numbers.alpha}$`))
  await searchFor(`#${numbers.alpha}.1`)
  await expect(page).toHaveURL(new RegExp(`/app/doc/${numbers.alpha}\\.1$`))
  await expect(main().getByRole('row', { name: escape(NAMES.alpha) })).toBeVisible()

  // The Matters scope, picked from the scope menu by keys, takes a matter number
  await tabTo(page, page.getByRole('button', { name: /Search In/ }), { from: box })
  await page.keyboard.press('Enter')
  await page.getByRole('menuitem', { name: 'Matters' }).focus()
  await page.keyboard.press('Enter')
  await expect(page.getByRole('button', { name: /Search In.*Matters/ })).toBeVisible()
  await searchFor('1.1')
  await expect(page).toHaveURL(new RegExp(`/app/folders/${matterFolder}$`))
  // Back to Documents for the word search: the same menu
  await tabTo(page, page.getByRole('button', { name: /Search In/ }), { from: box })
  await page.keyboard.press('Enter')
  await page.getByRole('menuitem', { name: 'Documents', exact: true }).focus()
  await page.keyboard.press('Enter')

  // A word, whose results come once the Indexer has read the documents (as finding.spec waits for them)
  await expect(async () => {
    await searchFor(`Alpha ${RUN}`)
    await expect(page).toHaveURL(/\/app\/search\?/)
    await expect(main().getByRole('row', { name: escape(NAMES.alpha) })).toBeVisible({ timeout: 4_000 })
  }).toPass({ timeout: 80_000, intervals: [1_000, 2_000, 3_000] })

  // The funnel is a button, reached by Tab from the star beside the folder's name, and pressed by Enter
  await openFolder()
  const funnel = page.getByRole('button', { name: /^Filters/ })
  await tabTo(page, funnel, { from: main().getByRole('button', { name: /Favorites/ }) })
  await page.keyboard.press('Enter')
  const filters = page.getByRole('region', { name: 'Filters' })
  await expect(filters).toBeVisible()
  // Every document here is a text file, so File Type offers Other; Space ticks it, and the chip says so
  await tabTo(page, filters.getByRole('checkbox', { name: /^Other/ }))
  await page.keyboard.press('Space')
  await expect(main().getByRole('group', { name: 'Filters in Use' })).toContainText('Other')
  await page.keyboard.press('Space')
  await expect(main().getByRole('group', { name: 'Filters in Use' })).toBeHidden()
})

test('row 4: Tab reaches the list, and each column sorts from its header by Enter', async () => {
  await openFolder()
  // Tab from the top of the page gets into the list without a trap: a person who can't point reaches it
  await page.locator('body').focus()
  let reached = false
  for (let i = 0; i < 60 && !reached; i++) {
    await page.keyboard.press('Tab')
    reached = await list().evaluate((grid) => grid.contains(document.activeElement))
  }
  expect(reached, 'Tab reaches the list').toBe(true)

  const sorted: [string, string][] = [
    ['Title', 'title'],
    ['Doc #', 'docNumber'],
    ['Version', 'version'],
    ['Author', 'author'],
    ['Edit Date', 'edited'],
  ]
  for (const [heading, column] of sorted) {
    const header = list().getByRole('columnheader', { name: escape(heading) })
    // Tab reached the list above; a header is a stop of its own that Shift+Tab and the arrow keys reach from a row
    await header.focus()
    await page.keyboard.press('Enter')
    await expect(page).toHaveURL(new RegExp(`sort=${column}`))
    await expect(header).toHaveAttribute('aria-sort', /ascending|descending/)
  }
  // The other way, from the same key
  const title = list().getByRole('columnheader', { name: /Title/ })
  await title.focus()
  await page.keyboard.press('Enter')
  await expect(title).toHaveAttribute('aria-sort', 'ascending')
  await page.keyboard.press('Enter')
  await expect(title).toHaveAttribute('aria-sort', 'descending')
  // The list is asked for again in the new order, so it is looked at until it is
  await expect
    .poll(async () => {
      const names = await list().getByRole('rowheader').allTextContents()
      return names.filter((name) => name.endsWith(`${RUN}.txt`))
    })
    .toEqual(Object.values(NAMES).filter((name) => name.endsWith('.txt')).sort((a, b) => b.localeCompare(a)))
})

test('row 5: Space opens Quick Look, the arrow keys walk the list, Esc closes it, and the pane docks by Enter', async () => {
  await openFolder('title&order=asc')
  await row(NAMES.pdf).focus()
  await page.keyboard.press('Space')
  const dialog = page.getByRole('dialog', { name: `Quick Look: ${NAMES.pdf}` })
  await expect(dialog).toBeVisible()
  await expect(dialog.getByText('Page 1 of 1')).toBeVisible()
  // Next and previous document, by the arrows
  await page.keyboard.press('ArrowRight')
  // Another document now: the dialog is named for it
  await expect(page.getByRole('dialog', { name: /^Quick Look: / })).toBeVisible()
  await expect(dialog).toBeHidden()
  await page.keyboard.press('ArrowLeft')
  await expect(page.getByRole('dialog', { name: `Quick Look: ${NAMES.pdf}` })).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(dialog).toBeHidden()
  await expect.poll(() => inRow(row(NAMES.pdf))).toBe(true)

  // Docked as the reading pane, which follows the arrow keys through the rows
  await page.keyboard.press('Space')
  await expect(dialog).toBeVisible()
  await tabTo(page, dialog.getByRole('button', { name: 'Open as Reading Pane' }))
  await page.keyboard.press('Enter')
  const pane = page.getByRole('region', { name: 'Preview' })
  await expect(pane).toBeVisible()
  await expect(dialog).toBeHidden()
  await expect(pane.getByRole('heading', { name: NAMES.pdf })).toBeVisible()
  await row(NAMES.pdf).focus()
  await page.keyboard.press('ArrowDown')
  await expect(pane.getByRole('heading')).not.toHaveText(NAMES.pdf)
  await tabTo(page, pane.getByRole('button', { name: 'Close Preview' }), { from: row(NAMES.pdf) })
  await page.keyboard.press('Enter')
  await expect(pane).toBeHidden()
})

test('row 6: an email reads in Quick Look, Show Pictures and Save to Matter are keys, and Esc closes', async () => {
  await openFolder('title&order=asc')
  const mail = row(`${SUBJECT}.eml`)
  await mail.focus()
  await page.keyboard.press('Space')
  const dialog = page.getByRole('dialog', { name: new RegExp(`^Quick Look: .*${SUBJECT}`) })
  await expect(dialog).toBeVisible()
  const body = dialog.frameLocator('iframe[title="Message Body"]')
  await expect(body.getByText(`Hello from the run ${RUN}`)).toBeVisible()
  expect(trackers).toEqual([])

  await tabTo(page, dialog.getByRole('button', { name: 'Show Pictures' }))
  await page.keyboard.press('Enter')
  await expect.poll(() => trackers.length).toBeGreaterThan(0)

  // Show Pictures loads the message again, and WebKit leaves the focus in the frame it reloaded, from where Tab stays in that
  // frame: the walk goes on from the attachment's Preview, the control before it
  await tabTo(page, dialog.getByRole('button', { name: `Save to Matter: ${ATTACHMENT}` }), { from: dialog.getByRole('button', { name: `Preview ${ATTACHMENT}` }) })
  await page.keyboard.press('Enter')
  await expect(dialog.getByText(/^Saved as # \d+$/)).toBeVisible()

  await page.keyboard.press('Escape')
  await expect(dialog).toBeHidden()
  await expect.poll(() => inRow(mail)).toBe(true)

  // The attachment saved is a document of the matter: Enter on its row opens it in the app, and the notice says what to do
  // if nothing opened
  await openFolder('title&order=asc')
  await row(ATTACHMENT).focus() // a row, as above
  await page.keyboard.press('Enter')
  await expect(page.getByText(/If nothing opened/)).toBeVisible()
})

test('row 7: Enter opens in the app, Check Out and Release are keys, and Edit changes a property and saves with Enter', async () => {
  await openFolder('title&order=asc')
  await row(NAMES.pdf).focus()
  await page.keyboard.press('Enter')
  // A PDF is a kind the helper opens (a text file would download instead). It isn't here, so the notice says what to do if
  // nothing opened
  await expect(page.getByText(/If nothing opened/)).toBeVisible()

  const details = page.getByRole('region', { name: 'Details' })
  await expect(details.getByRole('heading', { name: NAMES.pdf })).toBeVisible()
  // Download, by Tab from the row: the download event is the proof
  await tabTo(page, details.getByRole('button', { name: 'Download', exact: true }), { from: row(NAMES.pdf) })
  const [download] = await Promise.all([page.waitForEvent('download'), page.keyboard.press('Enter')])
  // WebKit suggests underscores for spaces
  expect(download.suggestedFilename().replaceAll('_', ' ')).toBe(NAMES.pdf)

  const checkOut = details.getByRole('button', { name: 'Check Out' })
  await tabTo(page, checkOut)
  await page.keyboard.press('Enter')
  await expect(details.getByText(/^Checked out by you since/)).toBeVisible()
  await tabTo(page, details.getByRole('button', { name: 'Release' }))
  await page.keyboard.press('Enter')
  await expect(details.getByText(/^Checked out by you since/)).toBeHidden()

  const edit = details.getByRole('button', { name: 'Edit' })
  // Grey while the document is asked for again after the release, and a key on a grey button does nothing
  await expect(edit).toBeEnabled()
  await tabTo(page, edit, { from: row(NAMES.pdf) })
  await page.keyboard.press('Enter')
  const comments = details.getByRole('textbox', { name: 'Comments' })
  // The form opens with the focus in its first field, since Edit went grey under the key that pressed it
  await expect(comments).toBeVisible()
  await expect.poll(() => details.locator('form').evaluate((form) => form.contains(document.activeElement))).toBe(true)
  // The first field is a list (Class); Comments is a Tab or two on
  await tabTo(page, comments)
  await page.keyboard.type(`Chase on Monday ${RUN}`)
  // Enter in a field of the form saves it, as a form does
  await page.keyboard.press('Enter')
  await expect(details.getByRole('button', { name: 'Save' })).toBeHidden()
  // The focus is back on Edit, whose key it is
  await expect(edit).toBeFocused()
  await page.keyboard.press('Enter')
  await expect(details.getByRole('textbox', { name: 'Comments' })).toHaveValue(`Chase on Monday ${RUN}`)
  // Esc cancels the form, and the focus goes back to Edit
  await page.keyboard.press('Escape')
  await expect(details.getByRole('textbox', { name: 'Comments' })).toBeHidden()
  await expect(edit).toBeFocused()
})

test('row 8: Upload opens the file picker by Enter and files what is chosen; with Option, the form comes first', async () => {
  await openFolder()
  const upload = main().getByRole('button', { name: 'Upload' })
  // The first control of the toolbar, which Tab reaches from the star beside the folder's name
  await tabTo(page, upload, { from: main().getByRole('button', { name: /Favorites/ }) })
  const chosen = `Uploaded ${RUN}.txt`
  const [picker] = await Promise.all([page.waitForEvent('filechooser'), page.keyboard.press('Enter')])
  await picker.setFiles({ name: chosen, mimeType: 'text/plain', buffer: Buffer.from(`uploaded ${RUN}`) })
  const tray = page.getByRole('region', { name: 'Filing' })
  await expect(tray.getByText(/^Filed as # \d+$/)).toBeVisible()
  await expect(row(chosen)).toBeVisible()

  // Option-Enter is the Option drop's key: the form asks first, and Enter in it files
  const withForm = `Uploaded with the form ${RUN}.txt`
  await tabTo(page, upload, { from: main().getByRole('button', { name: /Favorites/ }) })
  const [second] = await Promise.all([page.waitForEvent('filechooser'), page.keyboard.press('Alt+Enter')])
  await second.setFiles({ name: withForm, mimeType: 'text/plain', buffer: Buffer.from(`with the form ${RUN}`) })
  const form = page.getByRole('dialog', { name: 'File Documents' })
  await expect(form).toBeVisible()
  await tabTo(page, form.getByRole('button', { name: /^File/ }))
  await page.keyboard.press('Enter')
  await expect(form).toBeHidden()
  await expect(row(withForm)).toBeVisible()
})

test('row 9: New Folder, F2, Move To… and Delete are all keys', async () => {
  await openFolder('title&order=asc')

  // New Folder asks for a name, and Enter makes it
  const newFolder = main().getByRole('button', { name: 'New Folder' })
  await tabTo(page, newFolder, { from: main().getByRole('button', { name: /Favorites/ }) })
  await page.keyboard.press('Enter')
  const asking = page.getByRole('dialog', { name: 'New Folder' })
  await expect(asking.getByRole('textbox', { name: 'Name' })).toBeFocused()
  await page.keyboard.type(MADE)
  await page.keyboard.press('Enter')
  await expect(page.getByText(`Created ${MADE}`)).toBeVisible()
  await expect(row(MADE)).toBeVisible()
  // The focus goes back to the button that asked
  await expect(newFolder).toBeFocused()

  // F2 edits the title in the row, Enter saves it, and the row keeps the focus
  await row(NAMES.rename).focus()
  await expect(row(NAMES.rename)).toBeFocused()
  await page.keyboard.press('F2')
  const field = page.getByRole('textbox', { name: `New name of ${NAMES.rename}` })
  await expect(field).toBeFocused()
  await page.keyboard.type(`Renamed ${RUN}`)
  await page.keyboard.press('Enter')
  await expect(row(`Renamed ${RUN}.txt`)).toBeVisible()
  await expect(row(`Renamed ${RUN}.txt`)).toBeFocused()

  // More › Move To… by the menu's keys, then the picker's
  // The arrow keys select as they move: More acts on the selection, which a focus alone doesn't make
  await row(NAMES.move).focus()
  await page.keyboard.press('ArrowDown')
  await page.keyboard.press('ArrowUp')
  await expect(row(NAMES.move)).toHaveAttribute('aria-selected', 'true')
  const more = main().getByRole('button', { name: 'More', exact: true })
  await tabTo(page, more, { from: row(NAMES.move) })
  await page.keyboard.press('Enter')
  await page.getByRole('menuitem', { name: 'Move To…' }).focus() // the menu's own first key is ArrowDown
  await page.keyboard.press('Enter')
  const picker = page.getByRole('dialog', { name: 'Move 1 Document' })
  await expect(picker).toBeVisible()
  // Into the matter, down its folders to the one to move to, each shown before the next key
  for (const [step, shown] of [
    [/Smoke Matter/, /^Folders in .*Smoke Matter/],
    [/^Documents/, /^Folders in Documents$/],
    [/^E2E$/, /^Folders in E2E$/],
    [new RegExp(`^${WORK}`), new RegExp(`^Folders in ${WORK}$`)],
    [escape(TARGET), new RegExp(`^Folders in ${TARGET}$`)],
  ] as const) {
    // The picker's buttons are a list of choices: Tab to each
    const choice = picker.getByRole('button', { name: step }).first()
    await tabTo(page, choice, { limit: 300 })
    await page.keyboard.press('Enter')
    await expect(picker.getByRole('heading', { name: shown })).toBeVisible()
  }
  await tabTo(page, picker.getByRole('button', { name: 'Move Here' }))
  await page.keyboard.press('Enter')
  await expect(page.getByText(`Moved 1 document to ${TARGET}`)).toBeVisible()
  await expect(row(NAMES.move)).toHaveCount(0)
  // The dialog is gone and the focus is back on the page, not on the body
  await expect.poll(() => page.evaluate(() => document.activeElement !== document.body)).toBe(true)

  // Delete asks first, and Enter confirms
  // Down from the row above selects it, as arrowing to a row does; Delete acts on the selection
  await row(NAMES.charlie).focus()
  await page.keyboard.press('ArrowDown')
  await expect(row(NAMES.remove)).toHaveAttribute('aria-selected', 'true')
  await page.keyboard.press('Delete')
  const confirm = page.getByRole('dialog', { name: 'Move 1 document to the Trash?' })
  await expect(confirm).toBeVisible()
  await page.keyboard.press('Enter')
  await expect(page.getByText('Moved 1 document to the Trash')).toBeVisible()
  await expect(row(NAMES.remove)).toHaveCount(0)
  // The focus goes to the row that took its place, so the next key works on the list
  await expect.poll(() => inRow(list())).toBe(true)
})
