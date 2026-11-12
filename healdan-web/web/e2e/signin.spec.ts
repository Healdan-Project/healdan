// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { expect, test, type APIRequestContext, type Page } from '@playwright/test'
import { PASSWORD, authorized, deviceToken, fileDocument, matterFolders, signIn } from './helpers'

// Signing in to the User app (M6 spec sections 2.1 and 8): links into the app come back after the sign-in, the sign-in page
// lands in the app, the way to the Classic Interface and back, and Sign Out. The document a doc # link names is this
// run's own, filed through the API with a device token in matter 1.1's Documents folder (which the smoke fixture makes).
const RUN = `sgn${Date.now().toString(36)}`
const NAME = `Linked ${RUN}.txt`

let api: APIRequestContext | undefined
let token = ''
let linked = 0

async function personMenu(page: Page) {
  await page.getByRole('banner').getByRole('button', { name: /web/i }).click()
}

test.beforeAll(async ({ playwright }, testInfo) => {
  expect(PASSWORD, 'WEBUSER_PASSWORD is set by docker/tests/test-web.sh').not.toBe('')
  api = await playwright.request.newContext({ baseURL: testInfo.project.use.baseURL })
  token = await deviceToken(api, RUN)
  linked = await fileDocument(api, token, (await matterFolders(api, token)).documents, NAME, 'text/plain', Buffer.from(`linked ${RUN}`))
})

test.afterAll(async () => {
  if (!api) return
  if (token) await api.delete('/api/v1/auth/tokens/current', authorized(token))
  await api.dispose()
})

test('a link into the app signs in, then comes back to it', async ({ page }) => {
  await page.goto('/app/recent/folders')
  await expect(page).toHaveURL(/\/login\.jsp/)
  await signIn(page)
  await expect(page).toHaveURL(/\/app\/recent\/folders$/)
  await expect(page.getByRole('heading', { name: 'Recent Folders' })).toBeVisible()
})

test("a doc # link with its version, followed signed out, signs in and comes back to that document", async ({ page }) => {
  // The address a colleague's Copy Link gives, as a dotted one: version 1 of the document
  await page.goto(`/app/doc/${linked}.1`)
  await expect(page).toHaveURL(/\/login\.jsp/)
  await signIn(page)
  await expect(page).toHaveURL(new RegExp(`/app/doc/${linked}\\.1$`))
  await expect(page.getByRole('heading', { level: 1, name: `Document # ${linked}` })).toBeVisible()
  await expect(page.getByRole('region', { name: 'Details' }).getByRole('heading', { name: NAME })).toBeVisible()
})

test('signing in from the sign-in page lands in the app', async ({ page }) => {
  await page.goto('/login.jsp')
  await signIn(page)
  await expect(page).toHaveURL(/\/app\/$/)
  await expect(page.getByRole('heading', { name: 'Recent Documents' })).toBeVisible()
})

test('the Classic Interface and Open in Healdan go across and back, signed in', async ({ page }) => {
  await page.goto('/login.jsp')
  await signIn(page)
  await expect(page).toHaveURL(/\/app\/$/)
  await personMenu(page)
  await page.getByRole('menuitem', { name: 'Classic Interface' }).click()
  await expect(page).toHaveURL(/\/frontend\.jsp/)
  const open = page.getByText('Open in Healdan')
  await expect(open).toBeVisible()
  await open.click()
  await expect(page).toHaveURL(/\/app\/$/)
  await expect(page.getByRole('heading', { name: 'Recent Documents' })).toBeVisible()
})

test('Sign Out lands on the sign-in page, and the app then asks for sign-in again', async ({ page }) => {
  await page.goto('/login.jsp')
  await signIn(page)
  await expect(page).toHaveURL(/\/app\/$/)
  await personMenu(page)
  await page.getByRole('menuitem', { name: 'Sign Out' }).click()
  await expect(page).toHaveURL(/\/login\.jsp/)
  await page.goto('/app/')
  await expect(page).toHaveURL(/\/login\.jsp/)
})
