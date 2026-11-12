// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { expect, type APIRequestContext, type Locator, type Page } from '@playwright/test'

// webuser is made by healdan-core's SmokeWebUserSeed on the smoke stack; docker/tests/test-web.sh passes its password.
export const USERNAME = 'webuser'
export const PASSWORD = process.env.WEBUSER_PASSWORD ?? ''

// Fills the old UI's sign-in page and submits it. The page is a GWT app, so its fields have no labels to find them by.
export async function signIn(page: Page) {
  // The page keeps hidden copies of its fields, so only the visible ones count.
  const user = page.locator('input[placeholder="username"]:visible')
  const password = page.locator('input[placeholder="password"]:visible')
  // The smoke stack has a provider set up, so the password form is folded behind a link until it's chosen.
  const fold = page.getByText('Sign In with a Password')
  await expect(user.or(fold).first()).toBeVisible()
  if (!(await user.isVisible())) await fold.click()
  await user.fill(USERNAME)
  await password.fill(PASSWORD)
  await password.press('Enter')
}

// What the specs that file their own documents share. They file through the API with a device token, into matter 1.1's
// Documents folder (which the smoke fixture makes), with names that are the run's own, so nothing depends on what an
// earlier step left.

export async function call<T>(response: Promise<{ ok(): boolean; status(): number; json(): Promise<unknown> }>, what: string): Promise<T> {
  const answer = await response
  expect(answer.ok(), `${what}: HTTP ${answer.status()}`).toBe(true)
  return (answer.status() === 204 ? undefined : await answer.json()) as T
}

/** A device token for webuser, which the caller signs out with DELETE /auth/tokens/current when it is done */
export async function deviceToken(api: APIRequestContext, run: string): Promise<string> {
  const signedIn = await call<{ token: string }>(
    api.post('/api/v1/auth/tokens', { data: { username: USERNAME, password: PASSWORD, name: `e2e ${run}`, device: { id: `e2e-${run}` } } }),
    'a device token',
  )
  return signedIn.token
}

export function authorized(token: string) {
  return { headers: { Authorization: `Bearer ${token}` } }
}

/** Files a document and gives its doc # */
export async function fileDocument(api: APIRequestContext, token: string, folderId: number, name: string, mimeType: string, buffer: Buffer): Promise<number> {
  const made = await call<{ docNumber: number }>(
    api.post('/api/v1/documents', {
      ...authorized(token),
      multipart: {
        document: { name: 'document.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify({ folderId, checkOut: false })) },
        file: { name, mimeType, buffer },
      },
    }),
    `filing ${name}`,
  )
  return made.docNumber
}

/** Matter 1.1 of client 1, and its Documents folder */
export async function matterFolders(api: APIRequestContext, token: string): Promise<{ matter: number; documents: number }> {
  const get = <T>(path: string) => call<T>(api.get(`/api/v1${path}`, authorized(token)), `GET ${path}`)
  const matters = await get<{ items: { number: number; client: { number: number }; folderId: number }[] }>('/search?scope=matters&q=1.1')
  // Exactly matter 1 of client 1, not whatever else the search matched first
  const matter = matters.items.find((one) => one.client.number === 1 && one.number === 1)
  expect(matter, 'the smoke fixture has matter 1.1').toBeTruthy()
  const inside = await get<{ items: { id: number; name: string; type: string }[] }>(`/folders/${matter!.folderId}/children?type=folder`)
  const documents = inside.items.find((item) => item.type === 'folder' && item.name === 'Documents')
  expect(documents, 'matter 1.1 has a Documents folder').toBeTruthy()
  return { matter: matter!.folderId, documents: documents!.id }
}

/** A valid PDF of one page that says `text` in Helvetica, which no viewer needs to embed */
export function minimalPdf(text: string): Buffer {
  const objects = [
    '<< /Type /Catalog /Pages 2 0 R >>',
    '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
    '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 200] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>',
    `<< /Length ${`BT /F1 24 Tf 20 100 Td (${text}) Tj ET`.length} >>\nstream\nBT /F1 24 Tf 20 100 Td (${text}) Tj ET\nendstream`,
    '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
  ]
  let body = '%PDF-1.4\n'
  const offsets: number[] = []
  objects.forEach((object, i) => {
    offsets.push(body.length)
    body += `${i + 1} 0 obj\n${object}\nendobj\n`
  })
  const xref = body.length
  body += `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n`
  for (const offset of offsets) body += `${String(offset).padStart(10, '0')} 00000 n \n`
  body += `trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`
  return Buffer.from(body, 'latin1')
}

/**
 * The folder the specs that make folders of their own keep them in: `E2E`, inside Documents, found or made. Those specs
 * (`keyboard` and `a11y`) would otherwise add a row to Documents for each run, and the specs that look at Documents' own
 * list (`filing`, `working`) find their documents among the first rows, where the list draws them. The folders are paged
 * through, so a stack with many of them can't make a second `E2E`.
 */
export async function specsFolder(api: APIRequestContext, token: string, documents: number): Promise<number> {
  for (let offset = 0; ; ) {
    const inside = await call<{ items: { id: number; name: string; type: string }[]; total?: number }>(
      api.get(`/api/v1/folders/${documents}/children?type=folder&limit=200&offset=${offset}`, authorized(token)),
      'the folders of Documents',
    )
    const found = inside.items.find((item) => item.type === 'folder' && item.name === 'E2E')
    if (found) return found.id
    offset += inside.items.length
    if (inside.items.length === 0 || (inside.total !== undefined && offset >= inside.total)) break
  }
  return (await call<{ id: number }>(api.post('/api/v1/folders', { ...authorized(token), data: { parentId: documents, name: 'E2E' } }), 'making E2E')).id
}

/**
 * Presses Tab until `target` has the focus, as a person who doesn't point would, and fails after `limit` presses. `from`, when
 * given, is where the walk starts: a place the walkthrough reaches some other way, which the caller says why.
 */
export async function tabTo(page: Page, target: Locator, options: { from?: Locator; limit?: number } = {}) {
  if (options.from) await options.from.focus()
  const limit = options.limit ?? 60
  for (let i = 0; i <= limit; i++) {
    if (await target.evaluate((element) => element === document.activeElement).catch(() => false)) return
    await page.keyboard.press('Tab')
  }
  throw new Error(`Tab didn't reach ${target} in ${limit} presses`)
}
