// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { expect, test, type BrowserContext, type Page, type Request } from '@playwright/test'
import { expectNoViolations } from './axe'
import { ADMIN_PASSWORD, ADMIN_USERNAME, expectListUnder, signIn } from './helpers'

// An axe pass on Healdan Admin's Dashboard (admin spec 8.1 and 17.1), with its frame's find, Help and person menus, on
// Phase 2's screens (8.2 to 8.5: Clients, Matters, a matter's Overview and Workspace, and Workspace Templates), on
// Phase 3's (8.6: Stores, a Store's three tabs, Backends with the storage settings, and a backend's page, with the Add
// Backend, Key History and Change Backend dialogs) and on Phase 4's (8.7: Users, a person's page, Groups and a group's
// page, with the New User, Reset Password, Disable, New Group and Add Member dialogs), in the light theme and in the dark
// one, which Playwright's `colorScheme` switches as the system's setting would, and in a tablet-wide window with the left
// pane opened over the main area, where the Stores and Backends lists are checked too. A real browser draws the page, so
// this is where the screens' colour contrast is checked: the app's Vitest checks run in jsdom, which has no colours. It
// signs in as the stack's admin and depends on nothing an earlier step left. It changes nothing global: Phase 3's dialogs
// are closed with nothing sent, and Phase 4's are opened on a person and a group it makes, with names of its own run, in
// each theme, whose password it resets and whose Disable it cancels. The find and Phase 2's screens look at the smoke
// fixture's client and matter, and Phase 3's at its Store Smoke and the storage overlay's Smoke Garage, which every smoke
// stack has, and which it only reads.

for (const scheme of ['light', 'dark'] as const) {
  test.describe(`the Dashboard in the ${scheme} theme`, () => {
    // One sign-in per theme, and each test goes on from the same page
    test.describe.configure({ mode: 'serial' })
    let context: BrowserContext | undefined
    let page: Page
    /** The person and the group Phase 4's dialogs are opened on: this run's own, in this theme */
    let own = { username: '', person: '', group: '' }

    test.beforeAll(async ({ browser }, testInfo) => {
      expect(ADMIN_PASSWORD, 'ADMIN_PASSWORD is set by docker/tests/test-web.sh').not.toBe('')
      // A time and a random part, so two runs, the two themes or the two browsers never share a name
      const stamp = `${Date.now().toString(36)}${Math.floor(Math.random() * 36 ** 4).toString(36)}`
      const run = `${scheme} ${testInfo.project.name}-${stamp}`
      own = { username: `e2ea${scheme.slice(0, 1)}${testInfo.project.name.slice(0, 1)}${stamp}`, person: `E2E A11y ${run}`, group: `E2E A11y Group ${run}` }
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
    /**
     * The check, once React Aria's announcement of a button press that was still on its way has gone. A focused button
     * that goes pending (Create User, Reset Password, Create Group) is announced by its label, through a node that names
     * the button (`aria-labelledby`) and stays in React Aria's live region for 7 seconds. The button goes with its form
     * as the answer comes, and axe would find the node naming nothing until then, though the announcement was made while
     * the button was there.
     */
    const checkOnceAnnounced = async (what: string) => {
      await expect(page.locator('[data-live-announcer] [role="img"][aria-labelledby]'), 'the pending button announced').toHaveCount(0, { timeout: 10_000 })
      await check(what)
    }

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
      // The whole page with the list open: the list comes right after the box, inside the find's search landmark, and the
      // page around it stays shown to a screen reader (@healdan/ui's ComboBox), so nothing hidden holds a link
      // (aria-hidden-focus) and the page keeps its main landmark and heading. Its options, their headings and their colours
      // are checked with it.
      await check("the find's list")
      // Drawn over the window, under the find's field, though the field is positioned and padded
      await expectListUnder(banner().getByRole('search'))
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

    // Phase 2's screens (admin spec 8.2 to 8.5), opened from the left pane, with the smoke fixture's client 1 and matter 1.1,
    // which they only read
    test("Clients, Matters, a matter's Overview and Workspace, and Workspace Templates", async () => {
      const pane = page.getByRole('navigation', { name: 'Navigation' })
      const list = (name: string) => main().getByRole('grid', { name })

      await pane.getByRole('link', { name: 'Clients' }).click()
      await expect(list('Clients').getByRole('row', { name: /Smoke Client/ })).toBeVisible()
      await check('the Clients list')

      await pane.getByRole('link', { name: 'Matters' }).click()
      const smoke = list('Matters').getByRole('row', { name: /^1\.1 Smoke Matter/ })
      await expect(smoke).toBeVisible()
      await check('the Matters list')

      await smoke.click()
      await expect(main().getByRole('heading', { level: 1, name: '1.1 Smoke Matter' })).toBeVisible()
      await expect(main().getByRole('region', { name: 'Facts' })).toBeVisible()
      await check("a matter's Overview")
      await main().getByRole('tab', { name: 'Workspace' }).click()
      // Its own folder chosen, with who can use it
      await expect(main().getByRole('region', { name: 'Who Can Use This Folder' }).getByRole('table')).toBeVisible()
      await expect(main().getByRole('treegrid').getByRole('row').first()).toBeVisible()
      await check("a matter's Workspace")

      await pane.getByRole('link', { name: 'Workspace Templates' }).click()
      await expect(list('Workspace Templates').getByRole('row').nth(1)).toBeVisible()
      await check('Workspace Templates')

      // Back where the next test starts
      await pane.getByRole('link', { name: 'Dashboard' }).click()
      await expect(main().getByRole('heading', { level: 1, name: 'Dashboard' })).toBeVisible()
    })

    // Phase 3's screens (admin spec 8.6), opened from the left pane: the smoke fixture's Store Smoke on each of its tabs, and
    // the storage overlay's Smoke Garage, which they only read; with Change Backend on Smoke, Add Backend and Smoke Garage's
    // Key History, each closed with nothing sent
    test("Stores, a Store's tabs, Backends with the settings, and a backend's page, with their dialogs", async () => {
      const pane = page.getByRole('navigation', { name: 'Navigation' })
      const list = (name: string) => main().getByRole('grid', { name })
      // A list's row, by its first column: Smoke's row, not Smoke Cloud's
      const row = (name: string, rowName: string) => list(name).getByRole('row').filter({ has: page.getByRole('rowheader', { name: rowName, exact: true }) })
      // It only reads: no request it makes may change anything
      const changes: string[] = []
      const writes = (request: Request) => {
        if (!['GET', 'HEAD', 'OPTIONS'].includes(request.method())) changes.push(`${request.method()} ${new URL(request.url()).pathname}`)
      }
      page.on('request', writes)

      await pane.getByRole('link', { name: 'Stores' }).click()
      await expect(row('Stores', 'Smoke')).toBeVisible()
      // With the Deleted Stores under the list, whether it holds any or not
      await expect(main().getByRole('heading', { level: 2, name: 'Deleted Stores' })).toBeVisible()
      await expect(main().getByText('Loading…')).toHaveCount(0)
      await check('the Stores list')

      await row('Stores', 'Smoke').click()
      await expect(main().getByRole('heading', { level: 1, name: 'Smoke' })).toBeVisible()
      await expect(main().getByRole('grid', { name: 'Matters in Smoke' }).getByRole('row', { name: /^1\.1 Smoke Matter/ })).toBeVisible()
      await check("a Store's Matters")
      await main().getByRole('tab', { name: 'Who Can See It' }).click()
      await expect(main().getByRole('list', { name: 'Who Can See Smoke' }).getByRole('listitem').first()).toBeVisible()
      await check("a Store's Who Can See It")
      await main().getByRole('tab', { name: 'Storage' }).click()
      await expect(main().getByRole('tabpanel').getByText('Waiting to Move', { exact: true })).toBeVisible()
      await check("a Store's Storage")
      // Change Backend, opened on the backend Smoke keeps its files on, and cancelled
      await main().getByRole('button', { name: 'Change Backend' }).click()
      const change = page.getByRole('dialog', { name: 'Change the Backend of Smoke' })
      await expect(change.getByRole('radio', { name: /^Original/ })).toBeChecked()
      await expect(change.getByRole('radio', { name: /^Smoke Garage/ })).toBeVisible()
      await check('Change Backend')
      await change.getByRole('button', { name: 'Cancel' }).click()
      await expect(change).toBeHidden()

      await pane.getByRole('link', { name: 'Backends' }).click()
      await expect(row('Backends', 'Smoke Garage')).toBeVisible()
      // The settings are drawn once they've come
      await expect(main().getByRole('region', { name: 'Settings' }).getByRole('textbox', { name: 'Parallel Transfers' })).toBeVisible()
      await check('the Backends list, with the files with no Store and the settings')
      // Add Backend, opened on its S3 fields, as it starts, and cancelled
      await main().getByRole('button', { name: 'Add Backend' }).click()
      const add = page.getByRole('dialog', { name: 'Add a Storage Backend' })
      await expect(add.getByRole('radio', { name: 'S3' })).toBeChecked()
      await expect(add.getByRole('textbox', { name: 'Bucket' })).toBeVisible()
      await check('Add Backend')
      await add.getByRole('button', { name: 'Cancel' }).click()
      await expect(add).toBeHidden()

      await row('Backends', 'Smoke Garage').click()
      await expect(main().getByRole('heading', { level: 1, name: 'Smoke Garage' })).toBeVisible()
      await expect(main().getByText('Smoke Cloud', { exact: true })).toBeVisible()
      await check("a backend's page")
      // Its Key History: the seed's showing and confirmation of its recovery key
      await main().getByRole('button', { name: 'Key History' }).click()
      const history = page.getByRole('dialog', { name: 'Key History of Smoke Garage' })
      await expect(history.getByRole('row').nth(1)).toBeVisible()
      await check('Key History')
      await history.getByRole('button', { name: 'Close' }).click()
      await expect(history).toBeHidden()

      // Back where the next test starts
      await pane.getByRole('link', { name: 'Dashboard' }).click()
      await expect(main().getByRole('heading', { level: 1, name: 'Dashboard' })).toBeVisible()
      page.off('request', writes)
      expect(changes, "Phase 3's screens and dialogs, only read").toEqual([])
    })

    // Phase 4's screens (admin spec 8.7), opened from the left pane, with their dialogs, on a person and a group this run
    // makes in this theme: never webuser, apiuser, Everyone or admin's own account
    test("Users, a person's page, Groups and a group's page, with their dialogs", async () => {
      // Two accounts' worth of dialogs, each asking the server
      test.setTimeout(150_000)
      const pane = page.getByRole('navigation', { name: 'Navigation' })
      const list = (name: string) => main().getByRole('grid', { name })

      await pane.getByRole('link', { name: 'Users' }).click()
      await expect(list('Users').getByRole('row').nth(1)).toBeVisible()
      await expect(main().getByText('Loading…')).toHaveCount(0)
      await check('the Users list')

      await test.step('New User, and the temporary password it shows', async () => {
        await main().getByRole('button', { name: 'New User' }).click()
        const dialog = page.getByRole('dialog', { name: 'New User' })
        // Its groups to add, from the first by name, once they've come
        await expect(dialog.getByRole('listbox', { name: 'Groups' }).getByRole('option').first()).toBeVisible()
        await expect(dialog.getByRole('combobox', { name: 'Language' })).not.toHaveValue('')
        await check('New User')
        await dialog.getByRole('textbox', { name: 'Username' }).fill(own.username)
        await dialog.getByRole('textbox', { name: 'First Name' }).fill('E2E')
        await dialog.getByRole('textbox', { name: 'Last Name' }).fill(own.person.slice('E2E '.length))
        const create = dialog.getByRole('button', { name: 'Create User' })
        await expect(create).toBeEnabled()
        await create.click()
        const made = page.getByRole('dialog', { name: `Added ${own.person}` })
        await expect(made.getByRole('group', { name: 'Temporary Password' })).not.toBeEmpty()
        await checkOnceAnnounced("New User's temporary password")
        await made.getByRole('button', { name: 'Done' }).click()
        await expect(main().getByRole('heading', { level: 1, name: own.person })).toBeVisible()
      })

      await expect(main().getByRole('list', { name: `Groups of ${own.person}` }).getByRole('listitem').first()).toBeVisible()
      await expect(main().getByText('Loading…')).toHaveCount(0)
      await check("a person's page")

      await test.step('Reset Password: its question, and the temporary password it shows', async () => {
        await main().getByRole('button', { name: 'Reset Password' }).click()
        const question = page.getByRole('alertdialog', { name: `Reset the Password of ${own.person}?` })
        await expect(question).toBeVisible()
        await check("Reset Password's question")
        await question.getByRole('button', { name: 'Reset Password' }).click()
        const done = page.getByRole('dialog', { name: `New Password for ${own.person}` })
        await expect(done.getByRole('group', { name: 'Temporary Password' })).not.toBeEmpty()
        await checkOnceAnnounced("Reset Password's temporary password")
        await done.getByRole('button', { name: 'Done' }).click()
        await expect(done).toBeHidden()
      })

      await test.step("Disable's question, cancelled", async () => {
        await main().getByRole('button', { name: 'Disable', exact: true }).click()
        const question = page.getByRole('alertdialog', { name: `Disable ${own.person}?` })
        await expect(question).toBeVisible()
        await check("Disable's question")
        await question.getByRole('button', { name: 'Cancel' }).click()
        await expect(question).toBeHidden()
        await expect(main().getByRole('button', { name: 'Disable', exact: true })).toBeVisible()
      })

      await pane.getByRole('link', { name: 'Groups' }).click()
      await expect(list('Groups').getByRole('row').nth(1)).toBeVisible()
      await expect(main().getByText('Loading…')).toHaveCount(0)
      await check('the Groups list')

      await test.step('New Group', async () => {
        await main().getByRole('button', { name: 'New Group' }).click()
        const dialog = page.getByRole('dialog', { name: 'New Group' })
        await expect(dialog.getByRole('textbox', { name: 'Name' })).toBeFocused()
        await check('New Group')
        await dialog.getByRole('textbox', { name: 'Name' }).fill(own.group)
        const create = dialog.getByRole('button', { name: 'Create Group' })
        await expect(create).toBeEnabled()
        await create.click()
        await expect(main().getByRole('heading', { level: 1, name: own.group })).toBeVisible()
      })

      await expect(main().getByText('Nobody is in this group.')).toBeVisible()
      await checkOnceAnnounced("a group's page")

      await test.step("Add Member, with its list of people open, cancelled", async () => {
        await main().getByRole('button', { name: 'Add Member' }).click()
        const dialog = page.getByRole('dialog', { name: `Add a Member to ${own.group}` })
        await expect(dialog.getByRole('combobox', { name: 'Person' })).toBeVisible()
        await check('Add Member')
        // The person this theme made, found by their username: the list is drawn as the form's are, after the field
        await dialog.getByRole('combobox', { name: 'Person' }).fill(own.username)
        await expect(page.getByRole('option', { name: `${own.person} ${own.username}` })).toBeVisible()
        await check("Add Member's list of people")
        // Esc closes the list alone, then Cancel the dialog, with nobody added
        await page.keyboard.press('Escape')
        await expect(page.getByRole('listbox')).toBeHidden()
        await dialog.getByRole('button', { name: 'Cancel' }).click()
        await expect(dialog).toBeHidden()
        await expect(main().getByText('Nobody is in this group.')).toBeVisible()
      })

      // Back where the next test starts
      await pane.getByRole('link', { name: 'Dashboard' }).click()
      await expect(main().getByRole('heading', { level: 1, name: 'Dashboard' })).toBeVisible()
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
      // Phase 3's lists at the same width, each reached from the pane, which closes as its link is followed
      for (const screen of ['Stores', 'Backends']) {
        await navigation.click()
        await page.getByRole('navigation', { name: 'Navigation' }).getByRole('link', { name: screen }).click()
        await expect(page.getByRole('navigation', { name: 'Navigation' })).toHaveCount(0)
        await expect(main().getByRole('grid', { name: screen }).getByRole('row').nth(1)).toBeVisible()
        await expect(main().getByText('Loading…')).toHaveCount(0)
        await check(`the ${screen} list at 768 pixels`)
      }
      await navigation.click()
      await page.getByRole('navigation', { name: 'Navigation' }).getByRole('link', { name: 'Dashboard' }).click()
      await expect(main().getByRole('heading', { level: 1, name: 'Dashboard' })).toBeVisible()
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
