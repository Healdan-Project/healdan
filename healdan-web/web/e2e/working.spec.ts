// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { expect, test, type APIRequestContext, type Page } from '@playwright/test'
import { PASSWORD, authorized, deviceToken, fileDocument, matterFolders, minimalPdf, signIn } from './helpers'

// Working in the User app (M6 spec sections 6.4 to 6.6, and the tasks after it): Quick Look and reading an email today, with
// filing, renaming, moving and deleting to come. The documents are made here, through the API with a device token, in matter 1.1's
// Documents folder (which the smoke fixture makes), with names that are this run's own, so nothing depends on what an
// earlier step left. Both browser projects run this file against one stack, each with its own run.
const RUN = `wrk${Date.now().toString(36)}`
const PDF_NAME = `Quick Look ${RUN}.pdf`
const SUBJECT = `Quick Look email ${RUN}`
const ATTACHMENT_NAME = `Report ${RUN}.pdf`
// Hosts under .invalid never resolve: the test answers for them, and counts what reaches them
const TRACKER = 'https://tracker.invalid/pixel.gif'
const LINK = `https://link.invalid/report-${RUN}`

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
    '--=_mixed--',
    '',
  ]
  return Buffer.from(lines.join('\r\n'), 'utf-8')
}

let api: APIRequestContext | undefined
let token = ''
let documentsFolder = 0

test.beforeAll(async ({ playwright }, testInfo) => {
  expect(PASSWORD, 'WEBUSER_PASSWORD is set by docker/tests/test-web.sh').not.toBe('')
  api = await playwright.request.newContext({ baseURL: testInfo.project.use.baseURL })
  token = await deviceToken(api, RUN)
  documentsFolder = (await matterFolders(api, token)).documents
  await fileDocument(api, token, documentsFolder, PDF_NAME, 'application/pdf', minimalPdf(`Quick Look ${RUN}`))
  await fileDocument(api, token, documentsFolder, `${SUBJECT}.eml`, 'message/rfc822', trackingEmail())
})

test.afterAll(async () => {
  // beforeAll may have failed before there was anything to clean up
  if (!api) return
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
  await page.waitForLoadState('networkidle')
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

  // The attachment is a chip: Save to Matter files it as a document of its own, whose # the chip then shows
  await expect(dialog.getByRole('button', { name: `Preview ${ATTACHMENT_NAME}` })).toBeVisible()
  await dialog.getByRole('button', { name: `Save to Matter: ${ATTACHMENT_NAME}` }).click()
  await expect(dialog.getByText(/^Saved as # \d+$/)).toBeVisible()
  await expect(dialog.getByRole('button', { name: `Save to Matter: ${ATTACHMENT_NAME}` })).toBeHidden()
})
