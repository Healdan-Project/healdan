// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { expect, test, type APIRequestContext, type Page } from '@playwright/test'
import { PASSWORD, apiContext, authorized, call, deviceToken, escape, fileDocument, matterFolders, signIn, specsFolder } from './helpers'

// Checked Out to Me and Release… (spec 2026-11-17-checked-out-to-me, section 7.3): a document checked out on a computer,
// as the helper checks one out for Word, is in the person's list with that computer and app, and releasing it from the web
// page warns first, then frees it, so the computer can't save to it again. The computer is a device token of this run's
// own, `e2e <run>`, which no Helper made, so its name shows as it is. The document is in a folder of its own inside E2E, a
// folder of matter 1.1's Documents folder (which the smoke fixture makes) that the specs which make folders share. Other
// documents may be checked out to webuser, by earlier runs or steps, so the row is found by its name, and the count is
// compared with the list. Both browser projects run this file against one stack, each with its own run.
const RUN = `chk${Date.now().toString(36)}`
const FILE = `Checked out ${RUN}.docx`
const COMPUTER = `e2e ${RUN}`
const DOCX = 'application/vnd.openxmlformats-officedocument.wordprocessingml.document'

// CRC-32, as a zip entry carries it
const CRC_TABLE = Array.from({ length: 256 }, (_, n) => {
  let c = n
  for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
  return c >>> 0
})
function crc32(data: Buffer): number {
  let c = 0xffffffff
  for (const byte of data) c = CRC_TABLE[(c ^ byte) & 0xff]! ^ (c >>> 8)
  return (c ^ 0xffffffff) >>> 0
}

/**
 * A Word document of one paragraph that says `text`: the three parts Word needs, in a zip whose entries are stored as they
 * are (the server opens it with POI, to stamp its doc # in, as Word would save it)
 */
function minimalDocx(text: string): Buffer {
  const parts: [string, string][] = [
    [
      '[Content_Types].xml',
      '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">' +
        '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/>' +
        '<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>',
    ],
    [
      '_rels/.rels',
      '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">' +
        '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>',
    ],
    [
      'word/document.xml',
      '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>' +
        `<w:p><w:r><w:t>${text}</w:t></w:r></w:p></w:body></w:document>`,
    ],
  ]
  const entries: Buffer[] = []
  const directory: Buffer[] = []
  let offset = 0
  for (const [path, content] of parts) {
    const name = Buffer.from(path, 'utf-8')
    const data = Buffer.from(content, 'utf-8')
    const crc = crc32(data)
    // The local header: version 2.0, no flags, stored, 1 January 1980
    const local = Buffer.alloc(30)
    local.writeUInt32LE(0x04034b50, 0)
    local.writeUInt16LE(20, 4)
    local.writeUInt16LE(0x21, 12)
    local.writeUInt32LE(crc, 14)
    local.writeUInt32LE(data.length, 18)
    local.writeUInt32LE(data.length, 22)
    local.writeUInt16LE(name.length, 26)
    entries.push(local, name, data)
    // Its central directory entry, which says where the local header is
    const central = Buffer.alloc(46)
    central.writeUInt32LE(0x02014b50, 0)
    central.writeUInt16LE(20, 4)
    central.writeUInt16LE(20, 6)
    central.writeUInt16LE(0x21, 14)
    central.writeUInt32LE(crc, 16)
    central.writeUInt32LE(data.length, 20)
    central.writeUInt32LE(data.length, 24)
    central.writeUInt16LE(name.length, 28)
    central.writeUInt32LE(offset, 42)
    directory.push(central, name)
    offset += local.length + name.length + data.length
  }
  const listing = Buffer.concat(directory)
  const end = Buffer.alloc(22)
  end.writeUInt32LE(0x06054b50, 0)
  end.writeUInt16LE(parts.length, 8)
  end.writeUInt16LE(parts.length, 10)
  end.writeUInt32LE(listing.length, 12)
  end.writeUInt32LE(offset, 16)
  return Buffer.concat([...entries, listing, end])
}

let api: APIRequestContext | undefined
let token = ''
let docNumber = 0

test.beforeAll(async ({ playwright }, testInfo) => {
  expect(PASSWORD, 'WEBUSER_PASSWORD is set by docker/tests/test-web.sh').not.toBe('')
  api = await apiContext(playwright.request, testInfo.project.use.baseURL)
  token = await deviceToken(api, RUN)
  const where = await matterFolders(api, token)
  const folder = await call<{ id: number }>(
    api.post('/api/v1/folders', { ...authorized(token), data: { parentId: await specsFolder(api, token, where.documents), name: `Checked Out ${RUN}` } }),
    'making the folder',
  )
  docNumber = await fileDocument(api, token, folder.id, FILE, DOCX, minimalDocx(`Checked out ${RUN}`))
  // As the helper checks a document out when Word opens it: on its device, naming the app
  await call(api.post(`/api/v1/documents/${docNumber}/checkout`, { ...authorized(token), data: { app: 'Word' } }), 'checking out on the device')
})

test.afterAll(async () => {
  if (!api) return
  // Released however the tests ended, before the device's token goes; already free, it changes nothing
  if (token && docNumber) await api.delete(`/api/v1/documents/${docNumber}/checkout`, authorized(token))
  if (token) await api.delete('/api/v1/auth/tokens/current', authorized(token))
  await api.dispose()
})

const main = (page: Page) => page.getByRole('main')
const list = (page: Page) => main(page).getByRole('grid')
const entry = (page: Page) => page.getByRole('navigation', { name: 'Navigation' }).getByRole('link', { name: /^Checked Out to Me/ })
const row = (page: Page) => list(page).getByRole('row', { name: escape(FILE) })

/** What the entry is called with `n` documents checked out: the count is in its name from 1 up */
const entryName = (n: number) => (n === 0 ? 'Checked Out to Me' : `Checked Out to Me, ${n} document${n === 1 ? '' : 's'}`)

/**
 * The documents the list holds: its rows, but for the header's. The list is virtualised, so it draws only the rows on
 * screen, and says how many it has in `aria-rowcount`, which counts the header row as well.
 */
async function listed(page: Page): Promise<number> {
  const count = Number(await list(page).getAttribute('aria-rowcount'))
  expect(Number.isInteger(count) && count >= 1, `the list's aria-rowcount, ${count}`).toBe(true)
  return count - 1
}

/** The entry's count, as its badge shows it and its name says it, is the list's: the same answer feeds both */
async function expectCountIsTheList(page: Page): Promise<number> {
  const n = await listed(page)
  await expect(entry(page)).toHaveAccessibleName(entryName(n))
  if (n === 0) await expect(entry(page).getByText(/^\d+$/)).toHaveCount(0)
  else await expect(entry(page).getByText(/^\d+$/)).toHaveText(String(n))
  return n
}

/** Signs in and opens Checked Out to Me from its entry at the top of the left pane */
async function openCheckedOut(page: Page) {
  await page.goto('/login.jsp')
  await signIn(page)
  await expect(page.getByRole('heading', { name: 'Recent Documents' })).toBeVisible()
  await entry(page).click()
  await expect(page).toHaveURL(/\/app\/checked-out$/)
  await expect(main(page).getByRole('heading', { level: 1, name: 'Checked Out to Me' })).toBeVisible()
  await expect(entry(page)).toHaveAttribute('aria-current', 'page')
  await expect(row(page)).toBeVisible()
}

test("the list shows the document with the computer that holds it and its app, and the entry's count is the list's", async ({ page }) => {
  await openCheckedOut(page)
  // The computer's own name, as no Helper made it, with the app under it
  const computer = row(page).getByRole('gridcell').filter({ hasText: COMPUTER })
  await expect(computer).toHaveCount(1)
  await expect(computer.getByText(COMPUTER, { exact: true })).toBeVisible()
  await expect(computer.getByText('Word', { exact: true })).toBeVisible()
  expect(await expectCountIsTheList(page), 'this run checked out at least one').toBeGreaterThanOrEqual(1)
})

test('Release… warns that the computer loses what it has not saved; Cancel keeps the check-out, and Release Document frees it', async ({ page }) => {
  await openCheckedOut(page)
  const before = await expectCountIsTheList(page)
  await row(page).getByRole('rowheader').click()
  // The toolbar's, as the details beside the list have one of their own
  const release = main(page).getByRole('toolbar', { name: 'Checked Out Actions' }).getByRole('button', { name: 'Release…', exact: true })
  const warning = page.getByRole('alertdialog', { name: `Release ${FILE} from ${COMPUTER}?`, exact: true })

  // The warning lists the document under its computer, and Cancel has the focus, so Enter releases nothing
  await release.click()
  await expect(warning).toBeVisible()
  const documents = warning.getByRole('list', { name: `Documents on ${COMPUTER}` })
  await expect(documents).toContainText(FILE)
  await expect(documents).toContainText('Word')
  await expect(warning.getByRole('button', { name: 'Cancel' })).toBeFocused()
  await warning.getByRole('button', { name: 'Cancel' }).click()
  await expect(warning).toBeHidden()
  await expect(row(page)).toBeVisible()
  const kept = await call<{ hold: { heldOn?: { name?: string | null } | null } | null }>(api!.get(`/api/v1/documents/${docNumber}`, authorized(token)), 'the document after Cancel')
  expect(kept.hold?.heldOn?.name, 'still checked out on the computer').toBe(COMPUTER)

  // Release Document: the row leaves the list, the count drops, and the focus goes to the list, never the page's body
  await release.click()
  await expect(warning).toBeVisible()
  await warning.getByRole('button', { name: 'Release Document', exact: true }).click()
  await expect(warning).toBeHidden()
  await expect(page.getByText('Released 1 document', { exact: true })).toBeVisible()
  await expect(row(page)).toHaveCount(0)
  await expect(entry(page)).toHaveAccessibleName(entryName(before - 1))
  await expectCountIsTheList(page)
  await expect.poll(() => list(page).evaluate((grid) => grid.contains(document.activeElement))).toBe(true)

  // The server has it free, and the computer finds out at its next save, as Word's would be
  const freed = await call<{ hold: unknown }>(api!.get(`/api/v1/documents/${docNumber}`, authorized(token)), 'the document after the release')
  expect(freed.hold, 'free after the release').toBeNull()
  const version = { kind: 'snapshot', name: FILE, keepCheckedOut: true, ifHeld: true }
  const late = await api!.post(`/api/v1/documents/${docNumber}/versions`, {
    ...authorized(token),
    multipart: {
      version: { name: 'version.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(version)) },
      file: { name: FILE, mimeType: DOCX, buffer: minimalDocx(`Saved late ${RUN}`) },
    },
  })
  expect(late.status(), 'a save from the computer after the release').toBe(409)
  expect(((await late.json()) as { code?: string }).code).toBe('not-held')
})
