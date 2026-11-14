// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { readFile } from 'node:fs/promises'
import { expect, test, type APIRequestContext, type Page, type Request } from '@playwright/test'
import { PASSWORD, authorized, call, deviceToken, escape, escaped, fileDocument, matterFolders, minimalPdf, signIn } from './helpers'

// Working in the User app (M6 spec sections 6.4 to 6.6): Quick Look, reading an email, opening, downloading and checking out;
// filing, organising and the keys have specs of their own. The documents are made here, through the API with a device token, in matter 1.1's
// Documents folder (which the smoke fixture makes), with names that are this run's own, so nothing depends on what an
// earlier step left. Both browser projects run this file against one stack, each with its own run.
const RUN = `wrk${Date.now().toString(36)}`
const PDF_NAME = `Quick Look ${RUN}.pdf`
const SUBJECT = `Quick Look email ${RUN}`
const ATTACHMENT_NAME = `Report ${RUN}.pdf`
// No spaces: WebKit suggests underscores for them
const TEXT_NAME = `Schedule-${RUN}.txt`
// Hosts under .invalid never resolve: the test answers for them, and counts what reaches them
const TRACKER = 'https://tracker.invalid/pixel.gif'
const LINK = `https://link.invalid/report-${RUN}`
// Working with a document: downloaded, checked out and released, and its Comments edited. A text file, so the download's
// bytes can be compared with what was filed
const WORK_NAME = `Working-${RUN}.txt`
const WORK_TEXT = `The text of ${RUN}`
// Checked out and saved to through the API, as the helper does after Open in App, while the page shows it
const RETURN_NAME = `Returning-${RUN}.txt`

/** An email with an HTML body, a tracking picture and a link, and a PDF attached, as an .eml */
function trackingEmail(): Buffer {
  const pdf = minimalPdf(`Report ${RUN}`).toString('base64').replace(/(.{76})/g, '$1\r\n')
  const lines = [
    'From: Dunmere Pryce <dunmere@pryce.example>',
    'To: Priya Nair <priya@example.test>',
    `Subject: ${SUBJECT}`,
    'Date: Tue, 03 Nov 2026 10:00:00 +0000',
    `Message-ID: <tracker-${RUN}@pryce.example>`,
    'MIME-Version: 1.0',
    'Content-Type: multipart/mixed; boundary="=_mixed"',
    '',
    '--=_mixed',
    'Content-Type: text/html; charset="utf-8"',
    '',
    `<html><body><p style="color: #036">Hello from the run ${RUN}</p><p><a href="${LINK}">Open the report</a></p>`,
    `<img src="${TRACKER}?run=${RUN}" width="40" height="40" alt="Tracking pixel"></body></html>`,
    '',
    '--=_mixed',
    'Content-Type: application/pdf',
    'Content-Transfer-Encoding: base64',
    `Content-Disposition: attachment; filename="${ATTACHMENT_NAME}"`,
    '',
    pdf,
    '',
    '--=_mixed',
    'Content-Type: text/plain; charset="utf-8"',
    `Content-Disposition: attachment; filename="${TEXT_NAME}"`,
    '',
    `The schedule of ${RUN}`,
    '',
    '--=_mixed--',
    '',
  ]
  return Buffer.from(lines.join('\r\n'), 'utf-8')
}

let api: APIRequestContext | undefined
let token = ''
let documentsFolder = 0
let returning = 0

test.beforeAll(async ({ playwright }, testInfo) => {
  expect(PASSWORD, 'WEBUSER_PASSWORD is set by docker/tests/test-web.sh').not.toBe('')
  api = await playwright.request.newContext({ baseURL: testInfo.project.use.baseURL })
  token = await deviceToken(api, RUN)
  documentsFolder = (await matterFolders(api, token)).documents
  await fileDocument(api, token, documentsFolder, PDF_NAME, 'application/pdf', minimalPdf(`Quick Look ${RUN}`))
  await fileDocument(api, token, documentsFolder, `${SUBJECT}.eml`, 'message/rfc822', trackingEmail())
  await fileDocument(api, token, documentsFolder, WORK_NAME, 'text/plain', Buffer.from(WORK_TEXT))
  returning = await fileDocument(api, token, documentsFolder, RETURN_NAME, 'text/plain', Buffer.from(`The first text of ${RUN}`))
})

test.afterAll(async () => {
  // beforeAll may have failed before there was anything to clean up
  if (!api) return
  // Released however the test that holds it ended, before its device's token goes; already free, it answers a problem
  if (token && returning) await api.delete(`/api/v1/documents/${returning}/checkout`, authorized(token))
  if (token) await api.delete('/api/v1/auth/tokens/current', authorized(token))
  await api.dispose()
})

const main = (page: Page) => page.getByRole('main')
// Newest first: the folder lists a page of 100 at a time, and earlier smoke steps and runs filed documents into it, so by
// title a document just filed may not be in the first page
const folderPath = () => `/app/folders/${documentsFolder}?sort=edited&order=desc`

test('Quick Look draws a PDF under the page policy with no violation, and Esc gives the focus back to the row', async ({ page }) => {
  // The page's policy (AppServlet) allows no eval, no WebAssembly and no inline script or style, which PDF.js must run
  // without: any violation is reported to the page, and logged to its console.
  const logged: string[] = []
  page.on('console', (message) => logged.push(message.text()))
  page.on('pageerror', (error) => logged.push(error.message))
  await page.addInitScript(() => {
    const seen: string[] = []
    ;(window as unknown as { violations: string[] }).violations = seen
    document.addEventListener('securitypolicyviolation', (event) => seen.push(`${event.violatedDirective} ${event.blockedURI}`))
  })

  await page.goto('/login.jsp')
  await signIn(page)
  await expect(page.getByRole('heading', { name: 'Recent Documents' })).toBeVisible()
  // A fresh load of the folder, so the listener above is on the page that previews
  await page.goto(folderPath())
  const row = main(page).getByRole('row', { name: new RegExp(PDF_NAME.replace('.', '\\.')) })
  await expect(row).toBeVisible()

  // A click selects the row and leaves the focus on it; Space opens Quick Look, as Finder's does
  await row.getByRole('rowheader').click()
  await page.keyboard.press('Space')
  const dialog = page.getByRole('dialog', { name: `Quick Look: ${PDF_NAME}` })
  await expect(dialog).toBeVisible()
  await expect(dialog.getByText('Page 1 of 1')).toBeVisible()

  // The page is drawn: the canvas holds dark pixels where the text is, on PDF.js's white
  const canvas = dialog.getByRole('img', { name: 'Page 1' })
  await expect
    .poll(() =>
      canvas.evaluate((element) => {
        const target = element as HTMLCanvasElement
        const context = target.getContext('2d')
        if (!context || target.width === 0) return false
        const pixels = context.getImageData(0, 0, target.width, target.height).data
        for (let i = 0; i < pixels.length; i += 4) if ((pixels[i] ?? 255) < 128) return true
        return false
      }),
    )
    .toBe(true)

  expect(await page.evaluate(() => (window as unknown as { violations: string[] }).violations)).toEqual([])
  // CE's own policy header (security.csp) also logs that part of it is ignored, "invalid source http://server:port/",
  // which is CE's and not a violation of anything the page does; it's matched by neither check below (a follow-up to fix
  // it in CE's default policy is on the ledger).
  expect(logged.filter((text) => /^refused to|violates the following content security policy/i.test(text))).toEqual([])
  // PDF.js logs "Warning: …" and falls back when a standard font, a CMap or a decoder it asked for can't be fetched, which
  // the dark pixels above would not notice: this is the proof that its data files load under the policy
  expect(logged.filter((text) => /^warning: /i.test(text))).toEqual([])

  await page.keyboard.press('Escape')
  await expect(dialog).toBeHidden()
  await expect.poll(() => row.evaluate((element) => element.contains(document.activeElement))).toBe(true)
})

test('Quick Look shows an email as a message, with the sender\'s picture blocked until Show Pictures and its links in a new window', async ({ page }) => {
  // What reaches the hosts of the email: a tracker that learns the email was opened is the failure
  const trackers: string[] = []
  const links: string[] = []
  await page.context().route(/\.invalid\//, (route) => {
    const url = route.request().url()
    if (url.startsWith('https://tracker.invalid/')) {
      trackers.push(url)
      return route.fulfill({ status: 200, contentType: 'image/gif', body: Buffer.from('R0lGODlhAQABAAAAACw=', 'base64') })
    }
    links.push(url)
    return route.fulfill({ status: 200, contentType: 'text/html', body: '<title>Reached</title>' })
  })

  await page.goto('/login.jsp')
  await signIn(page)
  await expect(page.getByRole('heading', { name: 'Recent Documents' })).toBeVisible()
  await page.goto(folderPath())
  const row = main(page).getByRole('row', { name: new RegExp(SUBJECT) })
  await expect(row).toBeVisible()
  await row.getByRole('rowheader').click()
  await page.keyboard.press('Space')

  const dialog = page.getByRole('dialog', { name: new RegExp(`^Quick Look: .*${SUBJECT}`) })
  await expect(dialog).toBeVisible()
  await expect(dialog.getByRole('heading', { name: SUBJECT })).toBeVisible()
  await expect(dialog.getByText('Dunmere Pryce <dunmere@pryce.example>')).toBeVisible()
  await expect(dialog.getByText('Priya Nair <priya@example.test>')).toBeVisible()

  // The body renders in its frame, with no picture from the internet asked for yet
  const body = dialog.frameLocator('iframe[title="Message Body"]')
  await expect(body.getByText(`Hello from the run ${RUN}`)).toBeVisible()
  // The sender's own styles apply under the page's policy (style-src 'unsafe-inline')
  await expect(body.getByText(`Hello from the run ${RUN}`)).toHaveCSS('color', 'rgb(0, 51, 102)')
  // The frame has drawn its body, pictures and all that it would load; the route that answers for the tracker counts any ask
  expect(trackers).toEqual([])
  await expect(dialog.getByText('Pictures from the internet are blocked.')).toBeVisible()

  // Show Pictures reloads it with them, and only then does the tracker hear
  await dialog.getByRole('button', { name: 'Show Pictures' }).click()
  await expect(body.getByText(`Hello from the run ${RUN}`)).toBeVisible()
  await expect.poll(() => trackers.length).toBeGreaterThan(0)
  expect(trackers.every((url) => url.startsWith(`${TRACKER}?run=${RUN}`))).toBe(true)

  // The link opens a page of its own, which can't reach back to the email
  const [popup] = await Promise.all([page.waitForEvent('popup'), body.getByRole('link', { name: 'Open the report' }).click()])
  await expect(popup).toHaveURL(LINK)
  expect(await popup.evaluate(() => window.opener)).toBeNull()
  expect(links).toContain(LINK)
  await popup.close()

  // A chip that isn't a PDF or a picture downloads, through the web session as a navigation, under its own name
  const [download] = await Promise.all([page.waitForEvent('download'), dialog.getByRole('link', { name: `Download ${TEXT_NAME}` }).click()])
  expect(download.suggestedFilename()).toBe(TEXT_NAME)

  // The attachment is a chip: Save to Matter files it as a document of its own, whose # the chip then shows
  await expect(dialog.getByRole('button', { name: `Preview ${ATTACHMENT_NAME}` })).toBeVisible()
  await dialog.getByRole('button', { name: `Save to Matter: ${ATTACHMENT_NAME}` }).click()
  await expect(dialog.getByText(/^Saved as # \d+$/)).toBeVisible()
  await expect(dialog.getByRole('button', { name: `Save to Matter: ${ATTACHMENT_NAME}` })).toBeHidden()
})

/** Signs in, opens the folder and selects a document (the working one unless named), whose details then show */
async function selectWorkingDocument(page: Page, name = WORK_NAME) {
  await page.goto('/login.jsp')
  await signIn(page)
  await expect(page.getByRole('heading', { name: 'Recent Documents' })).toBeVisible()
  await page.goto(folderPath())
  const row = main(page).getByRole('row', { name: escape(name) })
  await expect(row).toBeVisible()
  await row.getByRole('rowheader').click()
  const details = page.getByRole('region', { name: 'Details' })
  await expect(details.getByRole('heading', { name })).toBeVisible()
  return details
}

test('Download saves the file as a plain link, under the document\'s name', async ({ page, browserName }, testInfo) => {
  const details = await selectWorkingDocument(page)
  // The web session carries the link: no script reads the bytes, and the browser's own download shows
  const [download] = await Promise.all([page.waitForEvent('download'), details.getByRole('button', { name: 'Download', exact: true }).click()])
  expect(download.suggestedFilename()).toBe(WORK_NAME)
  // WebKit's own copy of a download is gone before Playwright can read it in the container (ENOENT on its artifacts folder, whether
  // by path() or saveAs()), so only Chromium's bytes are compared with what was filed; both browsers prove the name
  if (browserName === 'chromium') {
    const saved = testInfo.outputPath(WORK_NAME)
    await download.saveAs(saved)
    expect(await readFile(saved, 'utf8')).toBe(WORK_TEXT)
  }
})

test('Check Out holds the document for you, and Release lets it go', async ({ page }) => {
  const details = await selectWorkingDocument(page)
  await details.getByRole('button', { name: 'Check Out' }).click()
  // The hold's own section, above the tabs: who has it and since when; the web page's check-out is on no computer
  const info = details.getByRole('group', { name: 'Check-Out Info' })
  await expect(info).toBeVisible()
  await expect(info.getByRole('definition').first()).toHaveText(/\(You\)$/)
  await expect(info.getByText('Computer', { exact: true })).toHaveCount(0)
  await expect(details.getByRole('button', { name: 'Check Out' })).toBeHidden()
  await expect(details.getByRole('button', { name: 'Check In' })).toBeVisible()

  await details.getByRole('button', { name: 'Release' }).click()
  await expect(info).toBeHidden()
  await expect(details.getByRole('button', { name: 'Check Out' })).toBeVisible()
})

test('Edit Properties changes a property in place, and the new value is there when it is edited again', async ({ page }) => {
  const details = await selectWorkingDocument(page)
  await details.getByRole('button', { name: 'Edit Properties' }).click()
  const comments = details.getByRole('textbox', { name: 'Comments' })
  await comments.fill(`Chase on Monday ${RUN}`)
  await details.getByRole('button', { name: 'Save' }).click()
  await expect(details.getByRole('button', { name: 'Save' })).toBeHidden()

  await details.getByRole('button', { name: 'Edit Properties' }).click()
  await expect(details.getByRole('textbox', { name: 'Comments' })).toHaveValue(`Chase on Monday ${RUN}`)
  await details.getByRole('button', { name: 'Cancel' }).click()
  await expect(details.getByRole('textbox', { name: 'Comments' })).toBeHidden()
})

// Row 7 of M6's walkthrough (spec 10.2): Open in App hands the document to the helper, which checks it out on its computer
// and saves to it from Excel, while the browser's window stays on screen behind Excel's. Coming back to it fires the
// window's focus, not visibilitychange, and what was asked a moment before is under 30 seconds old: the page asks again
// all the same. The browser here never leaves its window, so the test fires the focus the return would.
test('Coming back to the window shows a check-out and a save made on a computer, as the helper makes them', async ({ page }) => {
  // The details draw from the row while the document's own answer comes, and a return while an answer is on its way is met
  // by that answer, from before the change: each change through the API waits until the page has every answer it asked for
  const asking = new Set<Request>()
  page.on('request', (request) => {
    if (request.resourceType() === 'fetch') asking.add(request)
  })
  const answered = (request: Request) => void asking.delete(request)
  page.on('requestfinished', answered)
  page.on('requestfailed', answered)
  const settled = () => expect.poll(() => asking.size).toBe(0)

  const details = await selectWorkingDocument(page, RETURN_NAME)
  const meta = (version: string) => details.getByText(new RegExp(`^# ${returning} · Version ${escaped(version)} · `))
  // Check-Out Info names the computer, the token's name, for the person who holds it
  const held = details.getByRole('group', { name: 'Check-Out Info' }).getByText(`e2e ${RUN}`, { exact: true })
  // The row's marker says who has it, and nothing more
  const marker = main(page).getByRole('row', { name: escape(RETURN_NAME) }).getByRole('img', { name: 'Checked out by you', exact: true })
  const comeBack = () => page.evaluate(() => void window.dispatchEvent(new Event('focus')))
  await expect(meta('1')).toBeVisible()
  await expect(details.getByRole('button', { name: 'Check Out' })).toBeVisible()
  await expect(marker).toHaveCount(0)

  // The helper's check-out, held on its device, which the page isn't told of
  await settled()
  await call(api!.post(`/api/v1/documents/${returning}/checkout`, { ...authorized(token), data: {} }), 'checking out on the device')
  await comeBack()
  await expect(held).toBeVisible()
  await expect(details.getByRole('button', { name: 'Check Out' })).toBeHidden()
  await expect(marker).toBeVisible()

  // Excel's save while editing: the next snapshot, still checked out on the device
  await settled()
  const version = { kind: 'snapshot', name: RETURN_NAME, keepCheckedOut: true, ifHeld: true }
  await call(
    api!.post(`/api/v1/documents/${returning}/versions`, {
      ...authorized(token),
      multipart: {
        version: { name: 'version.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(version)) },
        file: { name: RETURN_NAME, mimeType: 'text/plain', buffer: Buffer.from(`The second text of ${RUN}`) },
      },
    }),
    'saving on the device',
  )
  await comeBack()
  await expect(meta('1.1')).toBeVisible()
  await details.getByRole('tab', { name: /^Versions/ }).click()
  await expect(details.getByRole('listitem').filter({ hasText: /^Version 1\.1/ })).toBeVisible()
  await expect(held).toBeVisible()
  await expect(marker).toBeVisible()
})
