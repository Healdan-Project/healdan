// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { expect, type APIRequest, type APIRequestContext, type Download, type Locator, type Page, type Response } from '@playwright/test'

// webuser is made by healdan-core's SmokeWebUserSeed on the smoke stack; docker/tests/test-web.sh passes its password.
export const USERNAME = 'webuser'
export const PASSWORD = process.env.WEBUSER_PASSWORD ?? ''
// The smoke stack's own administrator, whose password is the stack's published one (docker/tests/test-web.sh passes it):
// for what only an administrator sees, such as the Trash's Removed view. Never a real account's.
export const ADMIN_USERNAME = 'admin'
export const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD ?? ''

/** The text with the characters a regular expression takes for its own escaped */
export const escaped = (text: string) => text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
/** A pattern that finds the text anywhere in a name */
export const escape = (text: string) => new RegExp(escaped(text))

// Fills the old UI's sign-in page and submits it, as webuser or as `who`. The page is a GWT app, so its fields have no
// labels to find them by.
export async function signIn(page: Page, who: { username: string; password: string } = { username: USERNAME, password: PASSWORD }) {
  // The page keeps hidden copies of its fields, so only the visible ones count.
  const user = page.locator('input[placeholder="username"]:visible')
  const password = page.locator('input[placeholder="password"]:visible')
  // The page draws the password form first, then asks the server for its sign-in providers, and with any (the smoke stack
  // has one) folds the form behind a link when the answer comes. A look at the page can't tell a form that stays from one
  // about to be folded: a field found visible was folded away before it was filled, and the fill waited until the test's
  // time ran out, with no sign-in ever sent. So this waits for the mark the page puts on its body once that question is
  // settled, answered or not (HealdanSignInButtons): `folded` when the form went behind the link, which this then opens.
  const settled = page.locator('body[data-healdan-signin]')
  await expect(settled).toBeAttached()
  if ((await settled.getAttribute('data-healdan-signin')) === 'folded') await page.locator('[data-healdan-signin-link]').click()
  await user.fill(who.username)
  await password.fill(who.password)
  await password.press('Enter')
}

// What the specs that file their own documents share. They file through the API with a device token, into matter 1.1's
// Documents folder (which the smoke fixture makes), with names that are the run's own, so nothing depends on what an
// earlier step left.

/**
 * The request context a spec files, checks and cleans up through, at the stack's address. Each request has a connection
 * of its own (`Connection: close`). Playwright's context otherwise keeps connections for later requests, for as long as
 * they stay open, and Tomcat closes one that has waited 20 seconds (`connectionTimeout`): a request sent on one at that
 * moment fails with `socket hang up` (ECONNRESET). keyboard.spec's sign-out after its tests, 20 to 21 seconds after its
 * last call, failed so twice on 2026-11-14.
 */
export function apiContext(request: APIRequest, baseURL: string | undefined): Promise<APIRequestContext> {
  return request.newContext({ baseURL, extraHTTPHeaders: { Connection: 'close' } })
}

export async function call<T>(response: Promise<{ ok(): boolean; status(): number; json(): Promise<unknown> }>, what: string): Promise<T> {
  const answer = await response
  expect(answer.ok(), `${what}: HTTP ${answer.status()}`).toBe(true)
  return (answer.status() === 204 ? undefined : await answer.json()) as T
}

/**
 * A device token for webuser, or for `who`, which the caller signs out with DELETE /auth/tokens/current when it is done. A
 * second person's token for the same run is a device of its own.
 */
export async function deviceToken(api: APIRequestContext, run: string, who?: { username: string; password: string }): Promise<string> {
  const person = who ?? { username: USERNAME, password: PASSWORD }
  const device = who ? `${run}-${who.username}` : run
  const signedIn = await call<{ token: string }>(
    api.post('/api/v1/auth/tokens', { data: { username: person.username, password: person.password, name: `e2e ${device}`, device: { id: `e2e-${device}` } } }),
    'a device token',
  )
  return signedIn.token
}

export function authorized(token: string) {
  return { headers: { Authorization: `Bearer ${token}` } }
}

/**
 * Files a document and gives its doc #. `properties`, by name, are values for its properties (the filing's `properties`): a
 * value it would inherit inherits, any other becomes its own.
 */
export async function fileDocument(
  api: APIRequestContext,
  token: string,
  folderId: number,
  name: string,
  mimeType: string,
  buffer: Buffer,
  properties?: Record<string, string>,
): Promise<number> {
  const document = { folderId, checkOut: false, ...(properties ? { properties } : {}) }
  const made = await call<{ docNumber: number }>(
    api.post('/api/v1/documents', {
      ...authorized(token),
      multipart: {
        document: { name: 'document.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(document)) },
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

/**
 * Matter 1.4 of client 1, the smoke fixture's `Second Matter` (the `smoke-second-matter` step), which nothing else on the stack
 * moves or reads, and its Documents folder: a place for a folder to move to from matter 1.1, out of its matter
 */
export async function secondMatterFolders(api: APIRequestContext, token: string): Promise<{ matter: number; documents: number }> {
  const get = <T>(path: string) => call<T>(api.get(`/api/v1${path}`, authorized(token)), `GET ${path}`)
  const matters = await get<{ items: { number: number; client: { number: number }; folderId: number }[] }>('/search?scope=matters&q=1.4')
  const matter = matters.items.find((one) => one.client.number === 1 && one.number === 4)
  expect(matter, 'the smoke fixture has matter 1.4, Second Matter').toBeTruthy()
  const inside = await get<{ items: { id: number; name: string; type: string }[] }>(`/folders/${matter!.folderId}/children?type=folder`)
  const documents = inside.items.find((item) => item.type === 'folder' && item.name === 'Documents')
  expect(documents, 'matter 1.4 has a Documents folder').toBeTruthy()
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
 * (`keyboard`, `a11y` and `organising`) would otherwise add rows to Documents for each run, and the specs that look at Documents' own
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

/**
 * Where @healdan/ui's combo box draws its open list (ComboBox.module.css's `.layer`): in a layer as big as the window, so
 * nothing round the box holds what is fixed inside it (a transform, a filter or containment there would shrink the layer
 * and could cut the list off), with the list at the left edge of the field it lines up with, 4 pixels below it and as
 * wide as the box in it (React Aria's width for it). jsdom has no layout, so this is where it's checked.
 */
export async function expectListUnder(field: Locator) {
  const at = await field.boundingBox()
  const box = await field.getByRole('combobox').boundingBox()
  const drawn = await field
    .page()
    .getByRole('listbox')
    .evaluate((list) => {
      const rect = (element: Element | null) => {
        const box = element?.getBoundingClientRect()
        return box ? { x: box.x, y: box.y, width: box.width, height: box.height } : null
      }
      let layer = list.parentElement
      while (layer !== null && getComputedStyle(layer).position !== 'fixed') layer = layer.parentElement
      const page = document.documentElement
      return { layer: rect(layer), window: { x: 0, y: 0, width: page.clientWidth, height: page.clientHeight }, list: rect(list.closest('[data-trigger="ComboBox"]')) }
    })
  expect(drawn.layer, "the list's layer covers the window").toEqual(drawn.window)
  expect(at, 'the field is drawn').not.toBeNull()
  expect(box, 'the box is drawn').not.toBeNull()
  expect(drawn.list, 'the list is drawn').not.toBeNull()
  const off = { left: drawn.list!.x - at!.x, top: drawn.list!.y - (at!.y + at!.height + 4), width: drawn.list!.width - box!.width }
  for (const [what, by] of Object.entries(off)) expect(Math.abs(by), `the list's ${what} against the field's (off by ${by} pixels)`).toBeLessThanOrEqual(1)
}

/**
 * Starts a download with `start`, waits for it, and checks the server answered it with the file (`200`), and, when
 * `type` is given, with a `Content-Type` it matches. The download alone proves little: a browser can save a refusal's
 * body as readily as a file's, and under the name the page gave it, as WebKit did while the app's link had the
 * `download` attribute. The page then says nothing went wrong: the app takes a frame that loads a page for a refusal,
 * and each browser must tell it of none for a file.
 */
export async function downloadBy(page: Page, start: () => Promise<unknown>, expected: { type?: RegExp } = {}): Promise<Download> {
  const answers = new Map<string, { status: number; type: string }>()
  const note = (response: Response) =>
    void answers.set(response.url(), { status: response.status(), type: response.headers()['content-type'] ?? '' })
  page.on('response', note)
  try {
    const [download] = await Promise.all([page.waitForEvent('download'), start()])
    await expect.poll(() => answers.get(download.url())?.status, { message: `the server's answer to the download of ${download.url()}` }).toBe(200)
    if (expected.type) expect(answers.get(download.url())?.type, `the type of the download of ${download.url()}`).toMatch(expected.type)
    await expect(page.getByText('Something went wrong.')).toHaveCount(0)
    return download
  } finally {
    page.off('response', note)
  }
}
