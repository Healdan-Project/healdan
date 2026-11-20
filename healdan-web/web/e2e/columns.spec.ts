// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { expect, test, type APIRequestContext, type Locator, type Page } from '@playwright/test'
import { PASSWORD, apiContext, authorized, call, deviceToken, escape, fileDocument, matterFolders, minimalPdf, signIn, specsFolder } from './helpers'

// The list's columns (list columns spec sections 4.2 to 4.9, 8.3): resized by an edge and by keys, chosen in the Columns panel,
// moved by a heading's drag and by Move Left, a layout of its own beside the reading pane, Recent sorted in the browser, Reset
// Columns, and a layout kept on a wide window opened in a narrow one. What only a browser shows is checked on the way: the
// edge's 24 pixels centred on the heading's edge, its line and the drop line in the accent, the heading's hover colour off
// while the pointer is on its edge (a `:has()` rule), and a heading's pointer drag that sorts nothing. Each test has a browser
// context of its own, so its layouts start from the defaults, whatever another left. The documents are this run's own, in a
// folder of its own inside the specs' E2E folder in matter 1.1's Documents folder (which the smoke fixture makes); Recent
// Documents also lists what the other specs filed as webuser, so its order is checked against the whole list the API gives.
const RUN = `col${Date.now().toString(36)}`
const WORK = `Columns ${RUN}`
// Filed in this order, so Recent's own order (Last Used, newest first) is the PDF, Bravo, Alpha, Charlie, and Title's isn't
const NAMES = {
  charlie: `Charlie columns ${RUN}.txt`,
  alpha: `Alpha columns ${RUN}.txt`,
  bravo: `Bravo columns ${RUN}.txt`,
  pdf: `Pane columns ${RUN}.pdf`,
}

let api: APIRequestContext | undefined
let token = ''
let workFolder = 0

test.beforeAll(async ({ playwright }, testInfo) => {
  expect(PASSWORD, 'WEBUSER_PASSWORD is set by docker/tests/test-web.sh').not.toBe('')
  api = await apiContext(playwright.request, testInfo.project.use.baseURL)
  token = await deviceToken(api, RUN)
  const where = await matterFolders(api, token)
  const home = await specsFolder(api, token, where.documents)
  workFolder = (await call<{ id: number }>(api.post('/api/v1/folders', { ...authorized(token), data: { parentId: home, name: WORK } }), `making ${WORK}`)).id
  for (const name of [NAMES.charlie, NAMES.alpha, NAMES.bravo]) await fileDocument(api, token, workFolder, name, 'text/plain', Buffer.from(`The text of ${name}`))
  await fileDocument(api, token, workFolder, NAMES.pdf, 'application/pdf', minimalPdf(`Pane ${RUN}`))
})

test.afterAll(async () => {
  if (!api) return
  if (token) await api.delete('/api/v1/auth/tokens/current', authorized(token))
  await api.dispose()
})

const main = (page: Page) => page.getByRole('main')
const grid = (page: Page) => main(page).getByRole('grid')
const heading = (page: Page, name: string) => grid(page).getByRole('columnheader', { name, exact: true })
/** A heading's edge, React Aria's ColumnResizer: the element a pointer drags */
const edgeOf = (one: Locator) => one.locator('[data-resizable-direction]')
const width = async (one: Locator) => (await one.boundingBox())?.width ?? 0

/** The headings' column ids, left to right as drawn */
async function shown(page: Page): Promise<string[]> {
  return grid(page)
    .locator('[role="columnheader"]')
    .evaluateAll((all) => all.map((one) => ({ key: one.getAttribute('data-column-key') ?? '', x: one.getBoundingClientRect().x })).sort((a, b) => a.x - b.x).map((one) => one.key))
}

/** Each heading's drawn width, by column id */
async function widths(page: Page): Promise<Record<string, number>> {
  return grid(page)
    .locator('[role="columnheader"]')
    .evaluateAll((all) => Object.fromEntries(all.map((one) => [one.getAttribute('data-column-key') ?? '', Math.round(one.getBoundingClientRect().width)])))
}

/** The names of the rows drawn, top to bottom (the virtualiser may keep its rows' elements in another order) */
async function drawnNames(page: Page): Promise<string[]> {
  return grid(page)
    .locator('[role="row"]:has([role="rowheader"])')
    .evaluateAll((rows) =>
      rows
        .map((row) => ({ y: row.getBoundingClientRect().y, name: (row.querySelector('[role="rowheader"]') as HTMLElement).innerText.trim() }))
        .sort((a, b) => a.y - b.y)
        .map((one) => one.name),
    )
}

/** What this browser keeps for a kind of list (healdan.columns.<kind>), parsed, or null */
async function kept(page: Page, kind: string): Promise<unknown> {
  return page.evaluate((name) => {
    const value = localStorage.getItem(name)
    return value === null ? null : JSON.parse(value)
  }, `healdan.columns.${kind}`)
}

/** The colour the page's accent token is drawn in, as computed styles give colours */
async function accent(page: Page): Promise<string> {
  return page.evaluate(() => {
    const probe = document.createElement('div')
    probe.style.background = 'var(--hd-accent-fill)'
    document.body.append(probe)
    const colour = getComputedStyle(probe).backgroundColor
    probe.remove()
    return colour
  })
}

/** An element's pseudo-element's background colour, which no box of its own has */
const pseudoBackground = (one: Locator, pseudo: '::before' | '::after') => one.evaluate((element, which) => getComputedStyle(element, which).backgroundColor, pseudo)

async function signedIn(page: Page) {
  await page.goto('/login.jsp')
  await signIn(page)
  await expect(page.getByRole('heading', { name: 'Recent Documents' })).toBeVisible()
}

/** Recent Documents, with this run's documents among its rows */
async function openRecent(page: Page) {
  await signedIn(page)
  await expect(grid(page).getByRole('row', { name: escape(NAMES.bravo) })).toBeVisible()
}

async function openFolder(page: Page) {
  await signedIn(page)
  await page.goto(`/app/folders/${workFolder}`)
  await expect(grid(page).getByRole('row', { name: escape(NAMES.alpha) })).toBeVisible()
}

/** Presses the mouse at a point and moves it sideways in steps, as a person drags; the caller lets go */
async function press(page: Page, x: number, y: number, by: number) {
  await page.mouse.move(x, y)
  await page.mouse.down()
  await page.mouse.move(x + by, y, { steps: 10 })
}

/**
 * Holds a drag over a point until `shown` passes, as a hand rests there: WebKit sends a drag's dragover on a timer, not at each
 * move, so the pointer moves a pixel each way between looks, and the last dragover is at the point.
 */
async function holdDragOver(page: Page, x: number, y: number, shown: () => Promise<void>) {
  await expect(async () => {
    await page.mouse.move(x + 1, y)
    await page.mouse.move(x, y)
    await shown()
  }).toPass({ timeout: 10_000, intervals: [100, 200, 400] })
}

/** Opens a heading's menu by a right-click on its name, and picks an item */
async function fromMenu(page: Page, name: string, item: string) {
  await heading(page, name).getByText(name, { exact: true }).click({ button: 'right' })
  const menu = page.getByRole('menu', { name: `${name} Column` })
  await expect(menu).toBeVisible()
  await menu.getByRole('menuitem', { name: item, exact: true }).click()
  await expect(menu).toBeHidden()
}

test('resizes a column by its edge and by keys, and keeps it after a reload', async ({ page }) => {
  await openRecent(page)
  const matter = heading(page, 'Matter')
  const edge = edgeOf(matter)
  // The edge's slider, which is in reach only while a resize lasts
  const slider = matter.getByRole('slider', { name: 'Resize Matter' })
  // The edge is a target 24 pixels wide, centred on the heading's edge (WCAG 2.5.8), as the panes' dividers are
  const at = (await matter.boundingBox())!
  const box = (await edge.boundingBox())!
  expect(box.width).toBe(24)
  expect(Math.abs(box.x + 12 - (at.x + at.width)), "the edge's centre against the heading's end").toBeLessThanOrEqual(1)

  // A heading that sorts brightens while pointed at, but not while the pointer is on its edge, where a click doesn't sort
  const resting = await matter.evaluate((element) => getComputedStyle(element).color)
  await matter.getByText('Matter', { exact: true }).hover()
  await expect.poll(() => matter.evaluate((element) => getComputedStyle(element).color)).not.toBe(resting)
  await page.mouse.move(box.x + 6, box.y + box.height / 2)
  await expect(matter).toHaveAttribute('data-hovered', 'true')
  await expect.poll(() => matter.evaluate((element) => getComputedStyle(element).color)).toBe(resting)
  // The line on the edge shows in the accent while the edge is pointed at (after a moment's wait), and not before
  const colour = await accent(page)
  await expect.poll(() => pseudoBackground(edge, '::before')).toBe(colour)
  await page.mouse.move(at.x + 20, at.y + at.height / 2)
  await expect.poll(() => pseudoBackground(edge, '::before')).toBe('rgba(0, 0, 0, 0)')

  // By the pointer, on a list whose headings also drag (to move their columns): the edge resizes, and the line shows
  // throughout. Title, to its left, is fixed at its width then.
  const before = await widths(page)
  await press(page, box.x + box.width / 2, box.y + box.height / 2, 80)
  await expect(edge).toHaveAttribute('data-resizing', 'true')
  expect(await pseudoBackground(edge, '::before')).toBe(colour)
  await page.mouse.up()
  await expect.poll(async () => Math.round(await width(matter))).toBe(before.matter! + 80)
  // Nothing sorted: the list's own order, Last Used, still has the arrow
  await expect(heading(page, 'Last Used')).toHaveAttribute('aria-sort', 'descending')
  expect(new URL(page.url()).search).toBe('')

  // By keys, from the heading's menu: Resize Column puts the focus on the edge's slider, three steps to the right widen the
  // column 10 pixels each, and Enter ends the resize with the focus back on the heading's name
  await fromMenu(page, 'Matter', 'Resize Column')
  await expect(slider).toBeFocused()
  for (let i = 0; i < 3; i++) await page.keyboard.press('ArrowRight')
  await page.keyboard.press('Enter')
  await expect(matter.getByText('Matter', { exact: true })).toBeFocused()
  const widened = before.matter! + 80 + 30
  await expect.poll(async () => Math.round(await width(matter))).toBe(widened)
  const left = await widths(page)
  // Kept as the resize left them: Matter, and the columns to its left, which it fixed
  const layout = (await kept(page, 'recentDocuments')) as { v: number; shown?: string[]; widths: Record<string, number> }
  expect(layout.v).toBe(1)
  expect(layout.shown).toBeUndefined()
  expect(Object.keys(layout.widths).sort()).toEqual(['docNumber', 'matter', 'title', 'version'])
  expect(layout.widths.matter).toBe(widened)

  await page.reload()
  await expect(grid(page).getByRole('row', { name: escape(NAMES.bravo) })).toBeVisible()
  await expect.poll(() => widths(page)).toEqual(left)
})

test('adds Client from the Columns panel and moves it by drag and by Move Left', async ({ page }) => {
  await openRecent(page)
  const defaults = ['title', 'docNumber', 'version', 'matter', 'author', 'edited', 'lastUsed']
  expect(await shown(page)).toEqual(defaults)

  // The Columns button, at the end of the headings, opens the panel, which applies a tick at once and stays open
  const button = main(page).getByRole('button', { name: 'Columns', exact: true })
  await button.click()
  const panel = page.getByRole('dialog', { name: 'Columns' })
  await expect(panel).toBeVisible()
  await expect(panel.getByText('For Recent Documents')).toBeVisible()
  await expect(panel.getByRole('group', { name: 'Shown' }).getByRole('checkbox', { name: 'Title' })).toBeChecked()
  await panel.getByRole('group', { name: 'More Columns' }).getByText('Client', { exact: true }).click()
  await expect(panel.getByRole('group', { name: 'Shown' }).getByRole('checkbox', { name: 'Client' })).toBeChecked()
  await expect.poll(() => shown(page)).toEqual([...defaults, 'client'])
  await expect(panel).toBeVisible()
  // Coming back to the window asks for the list again, which leaves the panel open with its focus where it was
  const ticked = panel.getByRole('group', { name: 'Shown' }).getByRole('checkbox', { name: 'Client' })
  await expect(ticked).toBeFocused()
  const asked = page.waitForResponse((response) => new URL(response.url()).pathname === '/api/v1/documents/recent')
  await page.evaluate(() => void window.dispatchEvent(new Event('focus')))
  await asked
  await expect(panel).toBeVisible()
  await expect(ticked).toBeFocused()
  await page.keyboard.press('Escape')
  await expect(panel).toBeHidden()
  await expect(button).toBeFocused()

  // Dragged by the pointer along the headings, to just past Doc #'s middle: the drop line shows on Doc #'s edge, in the accent,
  // and the column goes there. A drag is no click, so the sort stays the list's own.
  const client = heading(page, 'Client')
  const from = (await client.getByText('Client', { exact: true }).boundingBox())!
  const docNumber = heading(page, 'Doc #')
  const to = (await docNumber.boundingBox())!
  const y = from.y + from.height / 2
  await press(page, from.x + from.width / 2, y, to.x + to.width * 0.75 - (from.x + from.width / 2))
  const colour = await accent(page)
  await holdDragOver(page, to.x + to.width * 0.75, y, async () => {
    await expect(docNumber).toHaveAttribute('data-drop-line', 'edge', { timeout: 500 })
    expect(await pseudoBackground(docNumber, '::after')).toBe(colour)
  })
  await page.mouse.up()
  await expect.poll(() => shown(page)).toEqual(['title', 'docNumber', 'client', 'version', 'matter', 'author', 'edited', 'lastUsed'])
  await expect(grid(page).locator('[data-drop-line]')).toHaveCount(0)
  await expect(heading(page, 'Last Used')).toHaveAttribute('aria-sort', 'descending')
  await expect(heading(page, 'Client')).toHaveAttribute('aria-sort', 'none')
  expect(new URL(page.url()).search).toBe('')

  // Move Left, in its heading's menu, puts it next to Title, which nothing goes before
  await fromMenu(page, 'Client', 'Move Left')
  const moved = ['title', 'client', 'docNumber', 'version', 'matter', 'author', 'edited', 'lastUsed']
  await expect.poll(() => shown(page)).toEqual(moved)
  expect(await kept(page, 'recentDocuments')).toEqual({ v: 1, shown: moved, widths: {} })

  await page.reload()
  await expect(grid(page).getByRole('row', { name: escape(NAMES.bravo) })).toBeVisible()
  expect(await shown(page)).toEqual(moved)
})

test("the reading pane's list keeps its own layout", async ({ page }) => {
  await openFolder(page)
  const full = ['title', 'docNumber', 'version', 'author', 'edited']
  expect(await shown(page)).toEqual(full)

  // Docked from Quick Look
  await grid(page).getByRole('row', { name: escape(NAMES.pdf) }).getByRole('rowheader').click()
  await page.keyboard.press('Space')
  const quickLook = page.getByRole('dialog', { name: `Quick Look: ${NAMES.pdf}` })
  await quickLook.getByRole('button', { name: 'Open as Reading Pane' }).click()
  const pane = page.getByRole('region', { name: 'Preview' })
  await expect(pane).toBeVisible()
  await expect.poll(() => shown(page)).toEqual(['title', 'docNumber', 'edited'])
  // Its panel says whose layout it changes
  await main(page).getByRole('button', { name: 'Columns', exact: true }).click()
  await expect(page.getByRole('dialog', { name: 'Columns' }).getByText('For Lists Beside the Reading Pane')).toBeVisible()
  await page.keyboard.press('Escape')

  await fromMenu(page, 'Doc #', 'Hide Doc #')
  await expect.poll(() => shown(page)).toEqual(['title', 'edited'])
  expect(await kept(page, 'beside')).toEqual({ v: 1, shown: ['title', 'edited'], widths: {} })

  // The full list is as it was
  await page.getByRole('button', { name: 'Close Preview' }).click()
  await expect(pane).toBeHidden()
  await expect.poll(() => shown(page)).toEqual(full)
  expect(await kept(page, 'folder')).toBeNull()

  // And beside the pane again, the pane's own
  await grid(page).getByRole('row', { name: escape(NAMES.pdf) }).getByRole('rowheader').click()
  await page.keyboard.press('Space')
  await quickLook.getByRole('button', { name: 'Open as Reading Pane' }).click()
  await expect(pane).toBeVisible()
  await expect.poll(() => shown(page)).toEqual(['title', 'edited'])
  await page.getByRole('button', { name: 'Close Preview' }).click()
})

test('sorts Recent › Documents by Title and back by Last Used', async ({ page }) => {
  await openRecent(page)
  // The whole list the server sends, in its own order: the newest used first
  const recent = await call<{ items: { name: string }[] }>(api!.get('/api/v1/documents/recent', authorized(token)), 'the recent documents')
  const own = recent.items.map((item) => item.name)
  // Title's order is the server's rule for titles: the file name lower-cased, by code unit, ties in the list's own order
  const byTitle = own.map((name, at) => ({ name, at })).sort((a, b) => {
    const [x, y] = [a.name.toLowerCase(), b.name.toLowerCase()]
    return x < y ? -1 : x > y ? 1 : a.at - b.at
  }).map((one) => one.name)
  const lastUsed = heading(page, 'Last Used')
  await expect(lastUsed).toHaveAttribute('aria-sort', 'descending')
  // This run's documents are among the newest, in the order they were filed, last first
  expect(own.slice(0, 4)).toEqual([NAMES.pdf, NAMES.bravo, NAMES.alpha, NAMES.charlie])
  const drawn = await drawnNames(page)
  expect(drawn).toEqual(own.slice(0, drawn.length))

  const title = heading(page, 'Title')
  await title.getByText('Title', { exact: true }).click()
  await expect(title).toHaveAttribute('aria-sort', 'ascending')
  await expect(lastUsed).toHaveAttribute('aria-sort', 'none')
  await expect(page).toHaveURL(/[?&]sort=title&order=asc/)
  await expect.poll(async () => {
    const names = await drawnNames(page)
    return names.length > 0 && names.every((name, at) => name === byTitle[at])
  }).toBe(true)

  // A date's first click is newest first, which for Last Used is the list's own order: the address keeps no sort for it
  await lastUsed.getByText('Last Used', { exact: true }).click()
  await expect(lastUsed).toHaveAttribute('aria-sort', 'descending')
  await expect(page).not.toHaveURL(/sort=/)
  await expect.poll(async () => {
    const names = await drawnNames(page)
    return names.length > 0 && names.every((name, at) => name === own[at])
  }).toBe(true)
})

test('Reset Columns puts the defaults back', async ({ page }) => {
  await openFolder(page)
  const full = ['title', 'docNumber', 'version', 'author', 'edited']
  const defaults = await widths(page)

  // A column hidden, one added and one widened, then the panel's Reset to Default
  await fromMenu(page, 'Version', 'Hide Version')
  await main(page).getByRole('button', { name: 'Columns', exact: true }).click()
  const panel = page.getByRole('dialog', { name: 'Columns' })
  await panel.getByRole('group', { name: 'More Columns' }).getByText('Size', { exact: true }).click()
  await expect.poll(() => shown(page)).toEqual(['title', 'docNumber', 'author', 'edited', 'size'])
  await panel.getByRole('button', { name: 'Reset to Default' }).click()
  await expect.poll(() => shown(page)).toEqual(full)
  await page.keyboard.press('Escape')
  expect(await kept(page, 'folder')).toBeNull()

  // Again, with a width, then Reset Columns from a heading's menu: the columns, their order and their widths
  await fromMenu(page, 'Author', 'Move Right')
  const docNumber = heading(page, 'Doc #')
  const edge = (await edgeOf(docNumber).boundingBox())!
  await press(page, edge.x + edge.width / 2, edge.y + edge.height / 2, 60)
  await page.mouse.up()
  await expect.poll(async () => Math.round(await width(docNumber))).toBe(defaults.docNumber! + 60)
  expect(await shown(page)).toEqual(['title', 'docNumber', 'version', 'edited', 'author'])
  await fromMenu(page, 'Edit Date', 'Reset Columns')
  await expect.poll(() => shown(page)).toEqual(full)
  await expect.poll(() => widths(page)).toEqual(defaults)
  expect(await kept(page, 'folder')).toBeNull()
  await page.reload()
  await expect(grid(page).getByRole('row', { name: escape(NAMES.alpha) })).toBeVisible()
  expect(await shown(page)).toEqual(full)
  expect(await widths(page)).toEqual(defaults)
})

test('a wide layout scrolls sideways in a narrow window', async ({ page }) => {
  await page.setViewportSize({ width: 1920, height: 1080 })
  await openFolder(page)
  // Resizing the last column fixes every column at its width then: the whole list's width at 1,920 pixels
  const edited = heading(page, 'Edit Date')
  const edge = (await edgeOf(edited).boundingBox())!
  await press(page, edge.x + edge.width / 2, edge.y + edge.height / 2, 40)
  await page.mouse.up()
  const wide = await widths(page)
  const layout = await kept(page, 'folder')
  expect(layout).toEqual({ v: 1, widths: wide })
  const sum = Object.values(wide).reduce((all, one) => all + one, 0)

  await page.setViewportSize({ width: 1280, height: 720 })
  await page.reload()
  await expect(grid(page).getByRole('row', { name: escape(NAMES.alpha) })).toBeVisible()
  // The list scrolls sideways rather than squeezing the columns, and the page itself doesn't
  const sideways = await grid(page).evaluate((list) => ({ scroll: list.scrollWidth, client: list.clientWidth }))
  expect(sideways.client).toBeLessThan(sum)
  expect(sideways.scroll).toBeGreaterThanOrEqual(sum)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true)
  // Every column keeps its width: those drawn at the list's start, and the rest once it has scrolled to its end (the list
  // draws only the columns in view)
  const atStart = await widths(page)
  await grid(page).evaluate((list) => list.scrollTo({ left: list.scrollWidth }))
  await expect(edited).toBeVisible()
  await expect.poll(async () => {
    const [box, list] = await Promise.all([edited.boundingBox(), grid(page).boundingBox()])
    return box !== null && list !== null && box.x + box.width <= list.x + list.width + 1
  }).toBe(true)
  expect({ ...atStart, ...(await widths(page)) }).toEqual(wide)
  // Rows keep their height
  const heights = await grid(page).locator('[role="row"]:has([role="rowheader"])').evaluateAll((rows) => rows.map((row) => Math.round(row.getBoundingClientRect().height)))
  expect(new Set(heights)).toEqual(new Set([34]))
  // Nothing kept changed
  expect(await kept(page, 'folder')).toEqual(layout)
})
