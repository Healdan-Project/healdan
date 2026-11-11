// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { expect, type Page } from '@playwright/test'

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
