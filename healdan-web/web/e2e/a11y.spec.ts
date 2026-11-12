// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { expect, test, type APIRequestContext, type BrowserContext, type Locator, type Page } from '@playwright/test'
import { expectNoViolations } from './axe'
import { PASSWORD, USERNAME, authorized, call, deviceToken, fileDocument, matterFolders, minimalPdf, signIn, specsFolder } from './helpers'

// Signed-in Devices fits its dialog: nothing scrolls sideways, every Revoke is inside the dialog, and at 1280 a device's name
// takes no more than two lines (the dialog is as wide as its table needs; at 768 a name may wrap between words further)
async function expectDevicesFit(dialog: Locator, width: number) {
  const fit = await dialog.evaluate((box) => {
    const edge = box.getBoundingClientRect()
    const area = box.querySelector('table')!.parentElement!
    const range = document.createRange()
    range.selectNodeContents(box.querySelector('tbody th')!)
    return {
      sideways: area.scrollWidth > area.clientWidth,
      outside: [...box.querySelectorAll('[data-revoke]')].filter((one) => {
        const at = one.getBoundingClientRect()
        return at.left < edge.left || at.right > edge.right
      }).length,
      // The name's own line boxes, not the cell's height, which holds padding, a border and the row the Revoke button sets
      lines: new Set([...range.getClientRects()].map((one) => Math.round(one.top))).size,
    }
  })
  expect(fit.sideways, `the table scrolls sideways at ${width}`).toBe(false)
  expect(fit.outside, `Revoke buttons outside the dialog at ${width}`).toBe(0)
  if (width >= 1280) expect(fit.lines, 'a name takes at most two lines').toBeLessThanOrEqual(2)
}

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
    // A device for the Revoke question to be about: its name is neutral, and the spec revokes it
    const device = `Spec device ${RUN} ${scheme}`

    test.beforeAll(async ({ browser }, testInfo) => {
      await call(api!.post('/api/v1/auth/tokens', { data: { username: USERNAME, password: PASSWORD, name: device, device: { id: `e2e-device-${RUN}-${scheme}` } } }), 'a device to revoke')
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
      // Every toolbar button is one height: a label never wraps, and a bar too narrow for its buttons wraps them whole
      const heights = await page.getByRole('toolbar').first().getByRole('button').evaluateAll((buttons) => buttons.map((one) => Math.round(one.getBoundingClientRect().height)))
      expect(new Set(heights).size, `toolbar buttons' heights ${heights.join(', ')}`).toBe(1)
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
      // The recount and the list are held while it is looked at: what is busy keeps AA contrast (R79). page.route doesn't
      // touch the page's policy.
      const held: (() => void)[] = []
      // type=other is the first parameter of the recount (`?type=other&facets=...`) and not of the list, so it is looked for
      // right after the `?` as well as after an `&`: a pattern that wanted an `&` before it held only the list, and the
      // recount, which sets Filters' aria-busy, answered at once and was sometimes gone before it was looked for.
      await page.route(new RegExp(`/api/v1/folders/${workFolder}/children\\?(.*&)?type=other(&|$)`), async (route) => {
        await new Promise<void>((resolve) => held.push(resolve))
        await route.continue()
      })
      await filters.getByText(/^Other/).click()
      await expect(main().getByRole('group', { name: 'Filters in Use' })).toBeVisible()
      await expect(filters.locator('[aria-busy="true"]')).toBeVisible()
      await expect.poll(() => held.length).toBeGreaterThan(0)
      // The last answer stays while the new one is asked for: its groups and counts, never zeros
      expect(await filters.locator('[data-count]').allTextContents(), 'counts shown while held').not.toContain('0')
      expect(await filters.locator('[data-count]').count(), 'the last answer stays on screen').toBeGreaterThan(0)
      await check('Filters while the counts are asked for again')
      for (const release of held) release()
      await page.unrouteAll({ behavior: 'wait' })
      await expect(filters.locator('[aria-busy="true"]')).toHaveCount(0)
      await check('Filters in use')
      await main().getByRole('button', { name: 'Clear All' }).click()
    })

    test('the list while it is asked for again in a new order, and the filing tray and form', async () => {
      await page.goto(`/app/folders/${workFolder}?sort=title&order=asc`)
      await expect(main().getByRole('row', { name: new RegExp(TEXT.replace('.', '\\.')) })).toBeVisible()
      const path = new RegExp(`/api/v1/folders/${workFolder}/children\\?.*sort=docNumber`)
      const held: (() => void)[] = []
      await page.route(path, async (route) => {
        await new Promise<void>((resolve) => held.push(resolve))
        await route.continue()
      })
      await main().getByRole('columnheader', { name: /Doc #/ }).click()
      await expect(main().locator('[aria-busy="true"]')).toBeVisible()
      await check('the list while it is asked for again')
      for (const release of held) release()
      await page.unrouteAll({ behavior: 'wait' })
      await expect(main().locator('[aria-busy="true"]')).toHaveCount(0)

      // The tray, with a file just filed
      await page.getByLabel('Choose Files to Upload').setInputFiles({ name: `Tray ${RUN}.txt`, mimeType: 'text/plain', buffer: Buffer.from('tray') })
      const tray = page.getByRole('region', { name: 'Filing' })
      await expect(tray.getByText(/^Filed as # \d+$/)).toBeVisible()
      await check('the filing tray')
      // The form that comes first, with Option (Option-Enter) on Upload
      await main().getByRole('button', { name: 'Upload' }).focus()
      const [chooser] = await Promise.all([page.waitForEvent('filechooser'), page.keyboard.press('Alt+Enter')])
      await chooser.setFiles({ name: `Form ${RUN}.txt`, mimeType: 'text/plain', buffer: Buffer.from('form') })
      await expect(page.getByRole('dialog', { name: 'File Documents' })).toBeVisible()
      await check('the File Documents form')
      await page.keyboard.press('Escape')
    })

    test('the rename field, the delete question and the Help menu', async () => {
      await page.goto(`/app/folders/${workFolder}?sort=title&order=asc`)
      const row = main().getByRole('row', { name: new RegExp(TEXT.replace('.', '\\.')) })
      await row.focus()
      await page.keyboard.press('ArrowDown')
      await page.keyboard.press('ArrowUp')
      await page.keyboard.press('F2')
      await expect(page.getByRole('textbox', { name: `New name of ${TEXT}` })).toBeFocused()
      await check('the rename field')
      await page.keyboard.press('Escape')
      await expect(row).toBeFocused()
      await page.keyboard.press('Delete')
      await expect(page.getByRole('dialog', { name: 'Move 1 document to the Trash?' })).toBeVisible()
      await check('the delete question')
      await page.keyboard.press('Escape')
      await page.getByRole('banner').getByRole('button', { name: 'Help' }).click()
      await expect(page.getByRole('menu')).toBeVisible()
      await check('the Help menu')
      await page.keyboard.press('Escape')
    })

    test('search results for a word, with their Filters', async () => {
      // The Indexer reads new documents a few seconds after they are filed (step 9 makes it fast), so the search is asked again
      // until the document shows
      await expect(async () => {
        await page.goto(`/app/search?scope=documents&q=${encodeURIComponent(RUN)}`)
        await expect(main().getByRole('row', { name: new RegExp(TEXT.replace('.', '\\.')) })).toBeVisible({ timeout: 4_000 })
      }).toPass({ timeout: 80_000, intervals: [1_000, 2_000, 3_000] })
      await check('document search results')
      await page.getByRole('button', { name: /^Filters/ }).click()
      await expect(page.getByRole('region', { name: 'Filters' })).toBeVisible()
      await expect(page.getByRole('region', { name: 'Filters' }).locator('[aria-busy="true"]')).toHaveCount(0)
      await check('document search Filters')
      // With Filters open the list is narrow: Title keeps the room it needs, and any text that doesn't fit ends in an ellipsis
      const cells = await main().getByRole('row').nth(1).evaluate((row) => {
        // The box that draws the ellipsis is the text's own or one around it inside the cell (a matter's client sits inside the box
        // that holds the matter number), and a flex box can't draw one
        const endsInEllipsis = (from: Element, cell: Element) => {
          for (let box: Element | null = from; box; box = box === cell ? null : box.parentElement) {
            const style = getComputedStyle(box)
            if (style.textOverflow === 'ellipsis' && style.display !== 'flex' && style.overflow === 'hidden') return true
          }
          return false
        }
        const holders = [...row.querySelectorAll('[role="gridcell"], [role="rowheader"]')].map((cell) => {
          const own = [...cell.querySelectorAll('*')].reverse().find((one) => [...one.childNodes].some((node) => node.nodeType === 3 && node.textContent!.trim())) ?? cell
          return { clipped: own.scrollWidth > own.clientWidth + 1 || cell.scrollWidth > cell.clientWidth + 1, ellipsis: endsInEllipsis(own, cell), title: cell.getAttribute('role') === 'rowheader' ? cell.getBoundingClientRect().width : 0 }
        })
        return { unclipped: holders.filter((one) => one.clipped && !one.ellipsis).length, title: Math.max(...holders.map((one) => one.title)) }
      })
      expect(cells.unclipped, 'cells whose text is cut with no ellipsis').toBe(0)
      expect(cells.title, "the Title column's width").toBeGreaterThanOrEqual(200)
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
      // The matter, then its client, with room between them: a button is a flex box, which drops the space of its text
      const apart = await page.getByRole('dialog', { name: 'Move 1 Document' }).locator('[data-client]').first().evaluate((client) => {
        // The text before it is the matter's: the one just ahead that isn't only the space a flex box drops
        let text = client.previousSibling
        while (text && !(text.textContent ?? '').trim()) text = text.previousSibling
        const before = document.createRange()
        before.selectNodeContents(text!)
        const boxes = before.getClientRects()
        return client.getBoundingClientRect().left - boxes[boxes.length - 1]!.right
      })
      expect(apart, 'the client starts clear of its matter').toBeGreaterThanOrEqual(4)
      await page.keyboard.press('Escape')

      // The person's menu is the last menu button of the bar (its name is the person's own)
      await page.getByRole('banner').locator('button[aria-haspopup]').last().click()
      await expect(page.getByRole('menu')).toBeVisible()
      await check('the person menu')
      await page.getByRole('menuitem', { name: 'Signed-in Devices' }).click()
      // The dialog is named by its title, which the Revoke question changes: the one dialog on the page is found without it
      const devices = page.getByRole('dialog')
      await expect(page.getByRole('dialog', { name: 'Signed-in Devices' })).toBeVisible()
      await expect(devices.getByRole('table', { name: 'Signed-in Devices' })).toBeVisible()
      await check('Signed-in Devices')
      await expectDevicesFit(devices, 1280)
      // The Revoke question, then its refusal (in the colour of what is wrong), then a revoke that goes through, which gives the
      // focus back to a control of the dialog, as a real WebKit and Chromium do
      await devices.getByRole('button', { name: `Revoke ${device}` }).click()
      await expect(devices.getByRole('heading', { name: `Revoke ${device}?` })).toBeVisible()
      await check('the Revoke question')
      const refuse = (route: { fulfill: (o: object) => Promise<void> }) =>
        route.fulfill({ status: 404, contentType: 'application/problem+json', body: JSON.stringify({ status: 404, code: 'not-found', title: 'Not found', detail: 'x' }) })
      await page.route(/\/api\/v1\/auth\/tokens\/\d+$/, refuse)
      await devices.getByRole('button', { name: 'Revoke', exact: true }).click()
      await expect(devices.getByRole('alert')).toBeVisible()
      await check('the Revoke refusal')
      await page.unroute(/\/api\/v1\/auth\/tokens\/\d+$/)
      await devices.getByRole('button', { name: 'Revoke', exact: true }).click()
      await expect(devices.getByRole('heading', { name: 'Signed-in Devices' })).toBeVisible()
      await expect.poll(() => devices.evaluate((dialog) => dialog.contains(document.activeElement))).toBe(true)
      // Esc still reaches the dialog from there
      await page.keyboard.press('Escape')
      await expect(devices).toBeHidden()
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
      await page.keyboard.press('Escape')
      // With a document selected, the details are drawn over the list
      await main().getByRole('row', { name: new RegExp(TEXT.replace('.', '\\.')) }).getByRole('rowheader').click()
      await page.getByRole('button', { name: 'Details and Filters' }).click()
      await expect(page.getByRole('region', { name: 'Details' }).getByRole('heading', { name: TEXT })).toBeVisible()
      await check('the details over the list at 768 pixels')
      await page.keyboard.press('Escape')
      // Signed-in Devices, whole at this width too
      await page.getByRole('banner').locator('button[aria-haspopup]').last().click()
      await page.getByRole('menuitem', { name: 'Signed-in Devices' }).click()
      const devices = page.getByRole('dialog', { name: 'Signed-in Devices' })
      await expect(devices.getByRole('table', { name: 'Signed-in Devices' })).toBeVisible()
      await expectDevicesFit(devices, 768)
      await page.keyboard.press('Escape')
      await page.setViewportSize({ width: 1280, height: 720 })
    })

    // Last: it ends the session
    test('the signed-out screen', async () => {
      await page.goto('/app/')
      await expect(page.getByRole('heading', { name: 'Recent Documents' })).toBeVisible()
      // The session ends under the page, as a timeout would end it, and the next request says so
      await page.evaluate(() => fetch('/api/v1/session', { method: 'DELETE', headers: { 'X-Healdan-Client': 'web' } }))
      await page.getByRole('navigation', { name: 'Navigation' }).getByRole('link', { name: 'Emails' }).click()
      await expect(page.getByText("You've been signed out.")).toBeVisible()
      await check('the signed-out screen')
    })
  })
}
