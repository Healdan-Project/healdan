// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { expect, test, type APIRequestContext, type Locator, type Page } from '@playwright/test'
import { PASSWORD, authorized, call, deviceToken, fileDocument, matterFolders, signIn } from './helpers'

// Filing by drag and drop (M6 spec section 6.5): three files dropped on a folder's list, one of them an email that is already
// in the matter, and one file dropped on a document's row as its next version. Playwright can't drag real files, so each
// drop is the events a browser makes (dragenter, dragover, drop) on the page's element, with a DataTransfer built in the page
// that holds Files. The documents are this run's own, in matter 1.1's Documents folder, which the smoke fixture makes.
const RUN = `fil${Date.now().toString(36)}`
const FIRST = `Dropped one ${RUN}.txt`
const SECOND = `Dropped two ${RUN}.txt`
const EMAIL = `Already filed ${RUN}.eml`
const VERSIONED = `Versioned ${RUN}.txt`

const email = Buffer.from(
  [
    'From: Dunmere Pryce <dunmere@pryce.example>',
    'To: Priya Nair <priya@example.test>',
    `Subject: Already filed ${RUN}`,
    'Date: Tue, 03 Nov 2026 10:00:00 +0000',
    `Message-ID: <filing-${RUN}@pryce.example>`,
    'MIME-Version: 1.0',
    'Content-Type: text/plain; charset="utf-8"',
    '',
    `Filed twice? ${RUN}`,
    '',
  ].join('\r\n'),
  'utf-8',
)

let api: APIRequestContext | undefined
let token = ''
let documentsFolder = 0
let emailNumber = 0
let versionedNumber = 0

test.beforeAll(async ({ playwright }, testInfo) => {
  expect(PASSWORD, 'WEBUSER_PASSWORD is set by docker/tests/test-web.sh').not.toBe('')
  api = await playwright.request.newContext({ baseURL: testInfo.project.use.baseURL })
  token = await deviceToken(api, RUN)
  documentsFolder = (await matterFolders(api, token)).documents
  emailNumber = await fileDocument(api, token, documentsFolder, EMAIL, 'message/rfc822', email)
  versionedNumber = await fileDocument(api, token, documentsFolder, VERSIONED, 'text/plain', Buffer.from(`First draft ${RUN}`))
})

test.afterAll(async () => {
  if (!api) return
  if (token) await api.delete('/api/v1/auth/tokens/current', authorized(token))
  await api.dispose()
})

const main = (page: Page) => page.getByRole('main')
// Newest first: the folder holds more than a page of documents by now, so the ones just filed are found by sorting
const folderPath = () => `/app/folders/${documentsFolder}?sort=edited&order=desc`

interface Dropped {
  name: string
  type: string
  text: string
}

/** What a browser does when files are dropped on `target`: dragenter, dragover and drop with a DataTransfer of Files */
async function dropFiles(target: Locator, files: Dropped[]) {
  await target.evaluate((element, dropped) => {
    const transfer = new DataTransfer()
    for (const one of dropped) transfer.items.add(new File([one.text], one.name, { type: one.type }))
    for (const type of ['dragenter', 'dragover', 'drop']) element.dispatchEvent(new DragEvent(type, { bubbles: true, cancelable: true, dataTransfer: transfer }))
  }, files)
}

test('files three dropped files, says an email already in the matter is, and saves a file dropped on a row as its next version', async ({ page }) => {
  await page.goto('/login.jsp')
  await signIn(page)
  await expect(page.getByRole('heading', { name: 'Recent Documents' })).toBeVisible()
  await page.goto(folderPath())
  const list = main(page).getByRole('grid')
  await expect(list.getByRole('row', { name: new RegExp(VERSIONED.replace('.', '\\.')) })).toBeVisible()

  // Three files on the list: two new ones, and the email the matter already holds
  await dropFiles(list, [
    { name: FIRST, type: 'text/plain', text: `The first of ${RUN}` },
    { name: SECOND, type: 'text/plain', text: `The second of ${RUN}` },
    { name: EMAIL, type: 'message/rfc822', text: email.toString('utf-8') },
  ])
  const tray = page.getByRole('region', { name: 'Filing' })
  await expect(tray.getByText(`Already in this matter as # ${emailNumber}`)).toBeVisible()
  await expect(tray.getByText(/^Filed as # \d+$/)).toHaveCount(2)
  await expect(tray.getByRole('button', { name: `Show ${EMAIL}` })).toBeVisible()

  // The new rows are in the list, filed by this person
  for (const name of [FIRST, SECOND]) await expect(list.getByRole('row', { name: new RegExp(name.replace('.', '\\.')) })).toBeVisible()
  // The email wasn't filed again
  const copies = await call<{ items: { copies: { document: { docNumber: number } }[] }[] }>(api!.get(`/api/v1/emails?messageId=${encodeURIComponent(`filing-${RUN}@pryce.example`)}`, authorized(token)), 'the email\'s copies')
  expect(copies.items.flatMap((one) => one.copies.map((copy) => copy.document.docNumber))).toEqual([emailNumber])

  // One file on a document's row asks, and Save as Version 2 saves it through POST /versions
  const row = list.getByRole('row', { name: new RegExp(VERSIONED.replace('.', '\\.')) })
  await dropFiles(row, [{ name: `Draft ${RUN}.txt`, type: 'text/plain', text: `Second draft ${RUN}` }])
  const question = page.getByRole('dialog', { name: `Save as Version 2 of # ${versionedNumber}, or File as a New Document?` })
  await expect(question).toBeVisible()
  await question.getByRole('button', { name: 'Save as Version 2' }).click()
  await expect(tray.getByText(`Saved as Version 2 of # ${versionedNumber}`)).toBeVisible()
  const saved = await call<{ version: string; name: string }>(api!.get(`/api/v1/documents/${versionedNumber}`, authorized(token)), 'the saved document')
  expect(saved).toMatchObject({ version: '2.0', name: VERSIONED })
})
