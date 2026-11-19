// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { expect, test, type BrowserContext, type Page } from '@playwright/test'
import { expectNoViolations } from './axe'
import { ADMIN_PASSWORD, ADMIN_USERNAME, signIn } from './helpers'

// An axe pass on Healdan Admin's Dashboard (admin spec 8.1 and 17.1), with its frame's find, Help and person menus, in the
// light theme and in the dark one, which Playwright's `colorScheme` switches as the system's setting would, and in a
// tablet-wide window with the left pane opened over the main area. A real browser draws the page, so this is where the
// Dashboard's colour contrast is checked: the app's Vitest checks run in jsdom, which has no colours. It signs in as the
// stack's admin and only reads, so it changes nothing on the stack and depends on nothing an earlier step left. The find
// looks for the smoke fixture's client and matter, which every smoke stack has.

for (const scheme of ['light', 'dark'] as const) {
  test.describe(`the Dashboard in the ${scheme} theme`, () => {
    // One sign-in per theme, and each test goes on from the same page
    test.describe.configure({ mode: 'serial' })
    let context: BrowserContext | undefined
    let page: Page

    test.beforeAll(async ({ browser }, testInfo) => {
      expect(ADMIN_PASSWORD, 'ADMIN_PASSWORD is set by docker/tests/test-web.sh').not.toBe('')
      context = await browser.newContext({ ...testInfo.project.use, colorScheme: scheme })
      page = await context.newPage()
      await page.goto('/admin/')
      await expect(page).toHaveURL(/\/login\.jsp/)
      await signIn(page, { username: ADMIN_USERNAME, password: ADMIN_PASSWORD })
      await expect(page).toHaveURL(/\/admin\/$/)
      await expect(main().getByRole('heading', { level: 1, name: 'Dashboard' })).toBeVisible()
    })

    test.afterAll(async () => {
      await context?.close()
    })

    const main = () => page.getByRole('main')
    const banner = () => page.getByRole('banner')
    const check = (what: string) => expectNoViolations(page, `${what} (${scheme})`)

    test('the page really is in the theme', async () => {
      expect(await page.evaluate(() => matchMedia('(prefers-color-scheme: dark)').matches)).toBe(scheme === 'dark')
      // The window's own surface, from the same custom property as the User app's, in either theme
      const background = await page.evaluate(() => getComputedStyle(document.body).backgroundColor)
      expect(background).toBe(scheme === 'dark' ? 'rgb(30, 30, 30)' : 'rgb(255, 255, 255)')
    })

    test('the Dashboard, with Needs Attention, the ten tiles and Usage drawn', async () => {
      await expect(main().getByText('Loading…')).toHaveCount(0)
      await expect(main().getByRole('region', { name: 'Server Health' }).getByRole('listitem')).toHaveCount(10)
      // Usage is worked out at the start, then every 15 minutes: once it has been, its figures, chart and lists are drawn.
      // It asks again every 30 seconds while it waits. A summary that failed says so, and fails here.
      await expect(main().getByRole('heading', { level: 3, name: 'Documents Filed, Last 30 Days' }), 'Usage shows its figures').toBeVisible({ timeout: 70_000 })
      await expect(main().getByRole('list', { name: 'Totals' })).toBeVisible()
      await check('the Dashboard')
    })

    test("the find's list, Help and the person menu", async () => {
      const find = banner().getByRole('combobox', { name: 'Find a client, matter or person' })
      await find.fill('Smoke')
      const found = page.getByRole('listbox')
      await expect(found.getByRole('option').first()).toBeVisible()
      // Client 1 and matter 1.1, each under its kind's heading
      await expect(found).toContainText('Smoke Client')
      await expect(found).toContainText('Smoke Matter')
      // The list on its own: while it's open, React Aria's combo box hides the rest of the page from screen readers
      // (aria-hidden on the top bar's other parts, the left pane and the main area, which hold links), so a check of the
      // whole page fails axe's aria-hidden-focus and its rules for the page's main landmark and heading, as the User app's
      // search box, the same component, would. The list sits in a popover outside every landmark, as React Aria draws
      // it, so axe's `region` isn't run on it. Its options, their headings and their colours are checked.
      await expectNoViolations(page, `the find's list (${scheme})`, '[role="listbox"]', ['region'])
      await page.keyboard.press('Escape')
      await find.fill('')
      await expect(found).toBeHidden()

      await banner().getByRole('button', { name: 'Help' }).click()
      await expect(page.getByRole('menuitem', { name: 'Admin Guide' })).toBeVisible()
      await check('the Help menu')
      await page.keyboard.press('Escape')
      await expect(page.getByRole('menu')).toBeHidden()

      await banner().locator('button[aria-haspopup]').last().click()
      await expect(page.getByRole('menuitem', { name: 'Sign Out' })).toBeVisible()
      await check('the person menu')
      await page.keyboard.press('Escape')
      await expect(page.getByRole('menu')).toBeHidden()
    })

    test('a tablet-wide window, with the left pane opened over the main area', async () => {
      await page.setViewportSize({ width: 768, height: 1024 })
      const navigation = banner().getByRole('button', { name: 'Navigation' })
      await expect(navigation).toBeVisible()
      await expect(page.getByRole('navigation', { name: 'Navigation' })).toHaveCount(0)
      // Nothing scrolls sideways, the top bar and the tiles included
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true)
      await check('the Dashboard at 768 pixels')
      await navigation.click()
      await expect(page.getByRole('navigation', { name: 'Navigation' })).toBeVisible()
      await check('the left pane at 768 pixels')
      // Esc closes it, and the focus goes back to the button that opened it
      await page.keyboard.press('Escape')
      await expect(page.getByRole('navigation', { name: 'Navigation' })).toHaveCount(0)
      await expect(navigation).toBeFocused()
      await page.setViewportSize({ width: 1280, height: 720 })
    })

    // Last: it ends the session
    test('the signed-out screen', async () => {
      // The session ends under the page, as a timeout would end it, and the next request, Refresh's, says so
      await page.evaluate(() => fetch('/api/v1/session', { method: 'DELETE', headers: { 'X-Healdan-Client': 'web' } }))
      await main().getByRole('button', { name: 'Refresh' }).click()
      await expect(page.getByText("You've been signed out.")).toBeVisible()
      await check('the signed-out screen')
    })
  })
}
