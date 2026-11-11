// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { expect, test, type APIRequestContext, type Page } from '@playwright/test'
import { PASSWORD, authorized, deviceToken, fileDocument, matterFolders, minimalPdf, signIn } from './helpers'

// Working in the User app (M6 spec sections 6.4 and 6.5, and the tasks after it): Quick Look today, with filing, renaming,
// moving and deleting to come. The documents are made here, through the API with a device token, in matter 1.1's
// Documents folder (which the smoke fixture makes), with names that are this run's own, so nothing depends on what an
// earlier step left. Both browser projects run this file against one stack, each with its own run.
const RUN = `wrk${Date.now().toString(36)}`
const PDF_NAME = `Quick Look ${RUN}.pdf`

let api: APIRequestContext | undefined
let token = ''
let documentsFolder = 0

test.beforeAll(async ({ playwright }, testInfo) => {
  expect(PASSWORD, 'WEBUSER_PASSWORD is set by docker/tests/test-web.sh').not.toBe('')
  api = await playwright.request.newContext({ baseURL: testInfo.project.use.baseURL })
  token = await deviceToken(api, RUN)
  documentsFolder = (await matterFolders(api, token)).documents
  await fileDocument(api, token, documentsFolder, PDF_NAME, 'application/pdf', minimalPdf(`Quick Look ${RUN}`))
})

test.afterAll(async () => {
  // beforeAll may have failed before there was anything to clean up
  if (!api) return
  if (token) await api.delete('/api/v1/auth/tokens/current', authorized(token))
  await api.dispose()
})

const main = (page: Page) => page.getByRole('main')

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
  await page.goto(`/app/folders/${documentsFolder}`)
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
