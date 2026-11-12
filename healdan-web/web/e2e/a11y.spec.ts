// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { expect, test, type APIRequestContext, type BrowserContext, type Page } from '@playwright/test'
import { expectNoViolations } from './axe'
import { PASSWORD, authorized, deviceToken, fileDocument, matterFolders, minimalPdf, signIn, specsFolder } from './helpers'

// An axe pass on each main view of the User app (M6 spec sections 5 and 15.1), in the light theme and in the dark one, which
// Playwright's `colorScheme` switches as the system's setting would. A real browser draws the page, so this is where colour
// contrast is checked (the Vitest checks run in jsdom, which has no colours). The documents are this run's own, in a folder
// of its own inside E2E, a folder of matter 1.1's Documents folder (which the smoke fixture makes) that the specs which make
// folders share.
const RUN = `a11y${Date.now().toString(36)}`
const PDF = `Accessible ${RUN}.pdf`
const TEXT = `Plain ${RUN}.txt`
const SUBJECT = `Accessible email ${RUN}`
const WORK = `Access ${RUN}`

function email(): Buffer {
  return Buffer.from(
    [
      'From: Dunmere Pryce <dunmere@pryce.example>',
      'To: Priya Nair <priya@example.test>',
      `Subject: ${SUBJECT}`,
      'Date: Tue, 03 Nov 2026 10:00:00 +0000',
      `Message-ID: <a11y-${RUN}@pryce.example>`,
      'MIME-Version: 1.0',
      'Content-Type: text/html; charset="utf-8"',
      '',
      `<html><body><p>Hello from the run ${RUN}</p></body></html>`,
      '',
    ].join('\r\n'),
    'utf-8',
  )
}

let api: APIRequestContext | undefined
let token = ''
let workFolder = 0
let matterFolder = 0
let pdfNumber = 0
let emailNumber = 0

test.beforeAll(async ({ playwright }, testInfo) => {
  expect(PASSWORD, 'WEBUSER_PASSWORD is set by docker/tests/test-web.sh').not.toBe('')
  api = await playwright.request.newContext({ baseURL: testInfo.project.use.baseURL })
  token = await deviceToken(api, RUN)
  const where = await matterFolders(api, token)
  matterFolder = where.matter
  const made = await api.post('/api/v1/folders', { ...authorized(token), data: { parentId: await specsFolder(api, token, where.documents), name: WORK } })
  expect(made.ok(), 'making the folder').toBe(true)
  workFolder = ((await made.json()) as { id: number }).id
  pdfNumber = await fileDocument(api, token, workFolder, PDF, 'application/pdf', minimalPdf(`Accessible ${RUN}`))
  await fileDocument(api, token, workFolder, TEXT, 'text/plain', Buffer.from(`plain ${RUN}`))
  emailNumber = await fileDocument(api, token, workFolder, `${SUBJECT}.eml`, 'message/rfc822', email())
})

test.afterAll(async () => {
  if (!api) return
  if (token) await api.delete('/api/v1/auth/tokens/current', authorized(token))
  await api.dispose()
})

for (const scheme of ['light', 'dark'] as const) {
  test.describe(`in the ${scheme} theme`, () => {
    // One sign-in per theme, and each test goes on from the same page
    test.describe.configure({ mode: 'serial' })
    let context: BrowserContext | undefined
    let page: Page

    test.beforeAll(async ({ browser }, testInfo) => {
      context = await browser.newContext({ ...testInfo.project.use, colorScheme: scheme })
      page = await context.newPage()
      await page.goto('/login.jsp')
      await signIn(page)
      await expect(page.getByRole('heading', { name: 'Recent Documents' })).toBeVisible()
    })

    test.afterAll(async () => {
      await context?.close()
    })

    const main = () => page.getByRole('main')
    const check = (what: string, within?: string) => expectNoViolations(page, `${what} (${scheme})`, within)

    test('the page really is in the theme', async () => {
      expect(await page.evaluate(() => matchMedia('(prefers-color-scheme: dark)').matches)).toBe(scheme === 'dark')
      // The window's own surface, from the same custom property in either theme
      const background = await page.evaluate(() => getComputedStyle(document.body).backgroundColor)
      expect(background).toBe(scheme === 'dark' ? 'rgb(30, 30, 30)' : 'rgb(255, 255, 255)')
    })

    test('Recent: Documents, Emails, Folders and Clients', async () => {
      await expect(page.getByRole('heading', { name: 'Recent Documents' })).toBeVisible()
      await check('Recent Documents')
      for (const [path, heading] of [
        ['emails', 'Recent Emails'],
        ['folders', 'Recent Folders'],
        ['clients', 'Recent Clients'],
      ] as const) {
        await page.goto(`/app/recent/${path}`)
        await expect(page.getByRole('heading', { name: heading })).toBeVisible()
        await check(heading)
      }
    })

    test("a matter's top level, with its folders", async () => {
      await page.goto(`/app/folders/${matterFolder}`)
      await expect(main().getByRole('heading', { level: 1 })).toContainText('Smoke Matter')
      await check("the matter's top level")
    })

    test('a folder, with a document selected and its details shown', async () => {
      await page.goto(`/app/folders/${workFolder}?sort=title&order=asc`)
      const row = main().getByRole('row', { name: new RegExp(TEXT.replace('.', '\\.')) })
      await expect(row).toBeVisible()
      await check('a folder')
      await row.getByRole('rowheader').click()
      await expect(page.getByRole('region', { name: 'Details' }).getByRole('heading', { name: TEXT })).toBeVisible()
      await check('the details')
      // The form that edits the properties
      await page.getByRole('region', { name: 'Details' }).getByRole('button', { name: 'Edit' }).click()
      await expect(page.getByRole('region', { name: 'Details' }).getByRole('button', { name: 'Save' })).toBeVisible()
      await check('the properties form')
      await page.getByRole('region', { name: 'Details' }).getByRole('button', { name: 'Cancel' }).click()
    })

    test('Filters, with one in use', async () => {
      await page.getByRole('button', { name: /^Filters/ }).click()
      const filters = page.getByRole('region', { name: 'Filters' })
      await expect(filters).toBeVisible()
      await check('Filters')
      // The checkbox itself is hidden under its label, as a person would click the words
      await filters.getByText(/^Other/).click()
      await expect(main().getByRole('group', { name: 'Filters in Use' })).toBeVisible()
      // The counts are dimmed for the moment they are asked for again, which is meant to show: look once they've settled
      await expect(filters.locator('[aria-busy="true"]')).toHaveCount(0)
      await check('Filters in use')
      await main().getByRole('button', { name: 'Clear All' }).click()
    })

    test('search results, in the Matters scope', async () => {
      await page.goto('/app/search?scope=matters&q=Smoke')
      await expect(main().getByRole('grid').getByRole('row').nth(1)).toBeVisible()
      await check('search results')
    })

    test("a document's own page", async () => {
      await page.goto(`/app/doc/${pdfNumber}`)
      await expect(main().getByRole('row', { name: new RegExp(PDF.replace('.', '\\.')) })).toBeVisible()
      await check("a document's page")
    })

    test('Quick Look on a PDF, on an email, and the docked reading pane', async () => {
      await page.goto(`/app/folders/${workFolder}?sort=title&order=asc`)
      await main().getByRole('row', { name: new RegExp(PDF.replace('.', '\\.')) }).focus()
      await page.keyboard.press('Space')
      const dialog = page.getByRole('dialog', { name: `Quick Look: ${PDF}` })
      await expect(dialog.getByText('Page 1 of 1')).toBeVisible()
      await check('Quick Look on a PDF')
      await dialog.getByRole('button', { name: 'Open as Reading Pane' }).click()
      await expect(page.getByRole('region', { name: 'Preview' })).toBeVisible()
      await expect(page.getByRole('region', { name: 'Preview' }).getByText('Page 1 of 1')).toBeVisible()
      await check('the reading pane')
      await page.getByRole('button', { name: 'Close Preview' }).click()

      await main().getByRole('row', { name: new RegExp(SUBJECT) }).focus()
      await page.keyboard.press('Space')
      const mail = page.getByRole('dialog', { name: new RegExp(`^Quick Look: .*${SUBJECT}`) })
      await expect(mail.frameLocator('iframe[title="Message Body"]').getByText(`Hello from the run ${RUN}`)).toBeVisible()
      await check('Quick Look on an email')
      await page.keyboard.press('Escape')
      expect(emailNumber).toBeGreaterThan(0)
    })

    test('the dialogs: New Folder, Move To…, Signed-in Devices, and the menus', async () => {
      await page.goto(`/app/folders/${workFolder}?sort=title&order=asc`)
      await main().getByRole('button', { name: 'New Folder' }).click()
      await expect(page.getByRole('dialog', { name: 'New Folder' })).toBeVisible()
      await check('New Folder')
      await page.keyboard.press('Escape')

      await main().getByRole('row', { name: new RegExp(TEXT.replace('.', '\\.')) }).getByRole('rowheader').click()
      await main().getByRole('button', { name: 'More', exact: true }).click()
      await expect(page.getByRole('menu')).toBeVisible()
      await check('the More menu')
      await page.getByRole('menuitem', { name: 'Move To…' }).click()
      await expect(page.getByRole('dialog', { name: 'Move 1 Document' })).toBeVisible()
      await check('the Move To… picker')
      await page.keyboard.press('Escape')

      // The person's menu is the last menu button of the bar (its name is the person's own)
      await page.getByRole('banner').locator('button[aria-haspopup]').last().click()
      await expect(page.getByRole('menu')).toBeVisible()
      await check('the person menu')
      await page.getByRole('menuitem', { name: 'Signed-in Devices' }).click()
      const devices = page.getByRole('dialog', { name: 'Signed-in Devices' })
      await expect(devices.getByRole('table', { name: 'Signed-in devices' })).toBeVisible()
      await check('Signed-in Devices')
      await page.keyboard.press('Escape')
    })

    test('a tablet-wide window, with the left pane opened over the list', async () => {
      await page.setViewportSize({ width: 768, height: 1024 })
      await page.goto(`/app/folders/${workFolder}?sort=title&order=asc`)
      const navigation = page.getByRole('banner').getByRole('button', { name: 'Navigation' })
      await expect(navigation).toBeVisible()
      // Nothing scrolls sideways, the top bar included
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true)
      await check('the folder at 768 pixels')
      await navigation.click()
      await expect(page.getByRole('navigation', { name: 'Navigation' })).toBeVisible()
      await check('the left pane at 768 pixels')
      await page.keyboard.press('Escape')
      await page.getByRole('button', { name: 'Details and Filters' }).click()
      await expect(page.getByRole('region', { name: 'Details and Filters' })).toBeVisible()
      await check('the right region at 768 pixels')
      await page.setViewportSize({ width: 1280, height: 720 })
    })
  })
}
