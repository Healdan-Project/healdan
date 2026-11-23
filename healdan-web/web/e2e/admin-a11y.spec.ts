// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { expect, test, type BrowserContext, type Locator, type Page, type Request, type Route } from '@playwright/test'
import { expectNoViolations } from './axe'
import { ADMIN_PASSWORD, ADMIN_USERNAME, expectListUnder, signIn, tabTo } from './helpers'

// An axe pass on Healdan Admin's Dashboard (admin spec 8.1 and 17.1), with its frame's find, Help and person menus, on
// Phase 2's screens (8.2 to 8.5: Clients, Matters, a matter's Overview and Workspace, and Workspace Templates), on
// Phase 3's (8.6: Stores, a Store's three tabs, Backends with the storage settings, and a backend's page, with the Add
// Backend, Key History and Change Backend dialogs) and on Phase 4's (8.7: Users, a person's page, Groups and a group's
// page, with the New User, Reset Password, Disable, New Group and Add Member dialogs) and on Phase 5's (8.8: Errors with a
// fault's details, the Server Log following and paused, the Audit Trail with a row's details and its Person and Matter
// lists open, Background Jobs with a task's Schedule… and Disable question, and a matter's and a person's Activity tabs)
// and on Phase 6's (8.9 and 13: the Search Index on each tab, Run Indexer Now greyed out, a running reindex's card with its
// Cancel question, the Reindex dialog on each of its five choices, and the User app's Reindex question), in the light theme
// and in the dark one, which Playwright's `colorScheme` switches as the system's setting would, and in a tablet-wide window
// with the left pane opened over the main area, where the Stores, Backends, Errors, Audit Trail and Search Index lists are
// checked too. A real browser draws the page, so this is where the screens' colour contrast is checked: the
// app's Vitest checks run in jsdom, which has no colours. It signs in as the stack's admin and depends on nothing an
// earlier step left. It changes nothing global: Phase 3's dialogs are closed with nothing sent, and Phase 4's are opened
// on a person and a group it makes, with names of its own run, in each theme, whose password it resets and whose Disable
// it cancels. Phase 5's resolve no fault and run, stop, switch or reschedule no job: Schedule… and Disable's question are
// cancelled with nothing sent. Phase 6's start no reindex and press neither Run Indexer Now, Try Again nor Skip: the Reindex
// dialog and question are cancelled, with only their counts asked for, and Run Indexer Now's refusal and the running card
// are drawn from answers the test gives the page itself. The find and Phase 2's screens look at the smoke fixture's client
// and matter, Phase 3's at its Store Smoke and the storage overlay's Smoke Garage, which every smoke stack has, and Phase
// 5's and Phase 6's at matter 1.1, all of which they only read.

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

      // Taken off whatever happens, so a failing step leaves it on no later test
      try {
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
      } finally {
        page.off('request', writes)
      }
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

    // Phase 5's screens (admin spec 8.8), opened from the left pane, whose Errors and Background Jobs entries can carry their
    // counts: Errors with the first fault's details, when there is one, the Server Log following and paused, the Audit
    // Trail with a row's details and its Person and Matter lists open, Background Jobs with Tags Processor's Schedule… and
    // Disable question, both cancelled, the focus back on the button each time, and the Activity tabs of matter 1.1, with a
    // To Date before its From Date, and of the person this theme made. It only reads: no request it makes may change
    // anything
    test('Errors, the Server Log, the Audit Trail, Background Jobs and the Activity tabs, with their dialogs', async () => {
      test.setTimeout(150_000)
      const pane = page.getByRole('navigation', { name: 'Navigation' })
      const entry = (name: string) => pane.getByRole('link', { name: new RegExp(`^${name}(,|$)`) })
      const list = (name: string) => main().getByRole('grid', { name })
      /** The rows of a list, without its headings */
      const rows = (name: string) => list(name).getByRole('row').filter({ has: page.getByRole('rowheader') })
      const changes: string[] = []
      const writes = (request: Request) => {
        if (!['GET', 'HEAD', 'OPTIONS'].includes(request.method())) changes.push(`${request.method()} ${new URL(request.url()).pathname}`)
      }
      page.on('request', writes)

      // Taken off whatever happens, so a failing step leaves it on no later test
      try {
        await test.step("Errors, with a fault's details", async () => {
          await entry('Errors').click()
          await expect(main().getByRole('heading', { level: 1, name: 'Errors' })).toBeVisible()
          await expect(list('Errors')).toBeVisible()
          await expect(main().getByText('Loading…')).toHaveCount(0)
          await check('the Errors list')
          // A fresh stack has faults of its own; a stack with none has no details to open
          if ((await rows('Errors').count()) > 0) {
            await rows('Errors').first().getByRole('rowheader').click()
            const details = main().getByRole('region', { name: 'Error Details' })
            await expect(details.getByRole('group', { name: 'Latest Message' })).not.toBeEmpty()
            await check("a fault's details")
          }
        })

        await test.step('the Server Log, following and paused', async () => {
          await entry('Server Log').click()
          await expect(main().getByRole('heading', { level: 1, name: 'Server Log' })).toBeVisible()
          await expect(main().getByRole('region', { name: 'Lines of Main Log' }).getByRole('listitem').first()).toBeVisible()
          await expect(main().getByRole('status').filter({ hasText: 'Following Live' })).toBeVisible()
          await check('the Server Log, following')
          await main().getByRole('button', { name: 'Pause', exact: true }).click()
          await expect(main().getByRole('status').filter({ hasText: 'Paused' })).toBeVisible()
          await check('the Server Log, paused')
          await main().getByRole('button', { name: 'Follow', exact: true }).click()
          await expect(main().getByRole('status').filter({ hasText: 'Following Live' })).toBeVisible()
        })

        await test.step("the Audit Trail, with a row's details and its pickers' lists open", async () => {
          await entry('Audit Trail').click()
          await expect(main().getByRole('heading', { level: 1, name: 'Audit Trail' })).toBeVisible()
          await expect(rows('Audit Trail').first()).toBeVisible()
          await expect(main().getByText('Loading…')).toHaveCount(0)
          // Refresh and Export CSV, buttons beside the title
          await expect(main().getByRole('button', { name: 'Refresh', exact: true })).toBeVisible()
          await expect(main().getByRole('button', { name: 'Export CSV' })).toBeVisible()
          await check('the Audit Trail')
          await rows('Audit Trail').first().getByRole('rowheader').click()
          await expect(main().getByRole('region', { name: 'Row Details' })).toBeVisible()
          await check("the Audit Trail with a row's details")
          // The person this theme made, by their username, and matter 1.1, each list drawn after its field, as the forms' are;
          // Esc closes each list, and the typing is taken away, with nothing picked
          const filters = main().getByRole('group', { name: 'Filters' })
          const person = filters.getByRole('combobox', { name: 'Person' })
          await person.fill(own.username)
          await expect(page.getByRole('option', { name: new RegExp(own.username) })).toBeVisible()
          await check("the Audit Trail's list of people")
          await page.keyboard.press('Escape')
          await expect(page.getByRole('listbox')).toBeHidden()
          await person.fill('')
          const matter = filters.getByRole('combobox', { name: 'Matter' })
          await matter.fill('Smoke Matter')
          await expect(page.getByRole('option', { name: /^1\.1 – Smoke Matter/ })).toBeVisible()
          await check("the Audit Trail's list of matters")
          await page.keyboard.press('Escape')
          await expect(page.getByRole('listbox')).toBeHidden()
          await matter.fill('')
          await expect(page).toHaveURL(/\/admin\/audit$/)
        })

        await test.step("Background Jobs, with a task's Schedule… and its Disable question, cancelled", async () => {
          await entry('Background Jobs').click()
          const table = main().getByRole('table', { name: 'Background Jobs' })
          await expect(table.getByRole('rowheader', { name: "LogicalDOC's Tasks" })).toBeVisible()
          await check('Background Jobs')
          const opens = table.getByRole('button', { name: 'Schedule of Tags Processor' })
          await opens.click()
          const schedule = page.getByRole('dialog', { name: 'Schedule of Tags Processor' })
          await expect(schedule.getByRole('radio', { name: 'Every So Often' })).toBeChecked()
          await check('Schedule…')
          await schedule.getByRole('button', { name: 'Cancel' }).click()
          await expect(schedule).toBeHidden()
          // Each closed, its focus goes back to the button that opened it
          await expect(opens).toBeFocused()
          const disable = table.getByRole('button', { name: 'Disable Tags Processor' })
          await disable.click()
          const question = page.getByRole('alertdialog', { name: 'Disable Tags Processor?' })
          await expect(question).toBeVisible()
          await check("Disable's question")
          await question.getByRole('button', { name: 'Cancel' }).click()
          await expect(question).toBeHidden()
          await expect(disable).toBeFocused()
        })

        await test.step("matter 1.1's Activity, and the person's", async () => {
          await entry('Matters').click()
          await list('Matters').getByRole('row', { name: /^1\.1 Smoke Matter/ }).click()
          await main().getByRole('tab', { name: 'Activity' }).click()
          await expect(rows('Activity of 1.1 Smoke Matter').first()).toBeVisible()
          await expect(main().getByText('Loading…')).toHaveCount(0)
          const panel = main().getByRole('tabpanel', { name: 'Activity' })
          await expect(panel.getByRole('button', { name: 'Refresh', exact: true })).toBeVisible()
          await check("a matter's Activity")
          // Its own days, a To Date before the From Date, which the tab says can keep no row: typed into each date's parts,
          // which go on to the next as each is filled. The days are the tab's own, and go with the page
          for (const [field, typed] of [['From Date', '01022026'], ['To Date', '01012026']]) {
            await panel.getByRole('group', { name: field }).getByRole('spinbutton', { name: new RegExp(`^month, ${field}`) }).click()
            await page.keyboard.type(typed)
          }
          await expect(panel.getByText('The To Date is before the From Date, so no row can match.')).toBeVisible()
          await expect(main().getByText('Loading…')).toHaveCount(0)
          await check("a matter's Activity with a To Date before its From Date")

          await entry('Users').click()
          await main().getByRole('group', { name: 'Filter Users' }).getByRole('textbox', { name: 'Name, Username or Email' }).fill(own.username)
          await rows('Users').filter({ has: page.getByRole('gridcell', { name: own.username, exact: true }) }).getByRole('rowheader').click()
          await expect(main().getByRole('heading', { level: 1, name: own.person })).toBeVisible()
          await main().getByRole('tab', { name: 'Activity' }).click()
          // Their account's making, its password's reset, and their groups
          await expect(rows(`Activity of ${own.person}`).first()).toBeVisible()
          await expect(main().getByText('Loading…')).toHaveCount(0)
          await check("a person's Activity")
        })

        // Back where the next test starts
        await pane.getByRole('link', { name: 'Dashboard' }).click()
        await expect(main().getByRole('heading', { level: 1, name: 'Dashboard' })).toBeVisible()
      } finally {
        page.off('request', writes)
      }
      expect(changes, "Phase 5's screens and dialogs, only read").toEqual([])
    })

    // Phase 6's screens (admin spec 8.9 and 13): the Search Index on each of its tabs; Run Indexer Now greyed out under the
    // focus, the heading asked again every 5 seconds while the Indexer runs, a reindex's card while it runs, with its Cancel
    // question, and while the server's worker fails at it, and Try Again refused while the index can't be written, its row
    // kept, all drawn from answers this test gives the page itself (the stack's own Indexer is left to its 5 seconds, a
    // reindex of the stack's runs too fast to be seen running, and no document of the stack's is tried again); the Reindex
    // dialog on each of its five choices, cancelled; and the User app's Reindex question, cancelled. It only reads: no
    // request it makes may change anything, only the dialog's and the question's counts (the preview) are sent as POSTs,
    // and the one Try Again it presses is answered by the test, never sent
    test("the Search Index, a reindex's card, the Reindex dialog on each choice, and the User app's Reindex question", async () => {
      test.setTimeout(150_000)
      const pane = page.getByRole('navigation', { name: 'Navigation' })
      const heading = main().getByRole('heading', { level: 1, name: 'Search Index' })
      const tab = (name: string) => main().getByRole('tab', { name: new RegExp(`^${name}( \\([\\d,]+\\))?$`) })
      const returned = () => page.evaluate(() => void window.dispatchEvent(new Event('focus')))
      /** A doc # no document of the stack's has, for the row Try Again is pressed on */
      const madeUpDocNumber = 999_999_901
      // What the test answers itself, which never reaches the server
      const answeredHere = new Set([`/api/v1/admin/search-index/failed/${madeUpDocNumber}/retry`])
      const changes: string[] = []
      const writes = (request: Request) => {
        const path = new URL(request.url()).pathname
        if (!['GET', 'HEAD', 'OPTIONS'].includes(request.method()) && path !== '/api/v1/admin/reindex/preview' && !answeredHere.has(path)) changes.push(`${request.method()} ${path}`)
      }
      page.on('request', writes)

      // Taken off whatever happens, so a failing step leaves it on no later test
      try {
        await test.step("the Search Index's tabs", async () => {
          await pane.getByRole('link', { name: /^Search Index(,|$)/ }).click()
          await expect(heading).toBeVisible()
          await expect(main().getByText('Loading…')).toHaveCount(0)
          await expect(tab("Couldn't Be Indexed")).toHaveAttribute('aria-selected', 'true')
          await expect(main().getByRole('table', { name: "Couldn't Be Indexed" }).or(main().getByText('Every document has been indexed or is waiting to be.'))).toBeVisible()
          await check("the Search Index, Couldn't Be Indexed")
          await tab('Waiting').click()
          await expect(main().getByRole('table', { name: 'Waiting' }).or(main().getByText('No document is waiting to be indexed.'))).toBeVisible()
          await check('the Search Index, Waiting')
          await tab('History').click()
          await expect(main().getByRole('heading', { level: 2, name: 'Reindexes' })).toBeVisible()
          await expect(main().getByRole('heading', { level: 2, name: 'Index Events' })).toBeVisible()
          await expect(main().getByText('Loading…')).toHaveCount(0)
          await check('the Search Index, History')
          await tab("Couldn't Be Indexed").click()
          await expect(page).toHaveURL(/\/admin\/search-index$/)
        })

        await test.step('Run Indexer Now, greyed out under the focus', async () => {
          // The heading's answer as the server gave it, read once through the page's own session (a request the test sent
          // itself would carry none of the browser's same-origin marks, and be refused), but for what Run Indexer Now would
          // be refused with: none, then a sentence of this test's own, as when the Indexer starts while the button has the
          // focus. Nothing is pressed.
          const index = await page.evaluate(async () => (await (await fetch('/api/v1/admin/search-index')).json()) as { indexer: Record<string, unknown> })
          const refused = 'The Indexer is running already.'
          let refusing = false
          const indexer = (route: Route) => route.fulfill({ json: { ...index, indexer: { ...index.indexer, runRefusal: refusing ? refused : null } } })
          const matched = (url: URL) => url.pathname === '/api/v1/admin/search-index'
          await page.route(matched, indexer)
          try {
            const run = main().getByRole('button', { name: 'Run Indexer Now' })
            await returned()
            await expect(run).toBeEnabled()
            await run.focus()
            refusing = true
            await returned()
            await expect(run).toBeDisabled()
            await expect(run).toHaveAccessibleDescription(refused)
            await expect(heading, 'the focus goes to the heading').toBeFocused()
            await check('Run Indexer Now greyed out')
          } finally {
            await page.unroute(matched, indexer)
          }
          await returned()
        })

        await test.step("a reindex's card while it runs, and its Cancel question, cancelled", async () => {
          // The reindexes waiting or running, answered by this test: one running reindex it makes up, which only the page
          // sees: its card, its progress bar and its question are what's checked
          const me = await page.evaluate(async () => (await (await fetch('/api/v1/me')).json()) as { id: number; username: string; name: string })
          const now = new Date()
          const job = {
            id: 2_000_000_000 + Math.floor(Math.random() * 100_000_000),
            kind: 'folder',
            targetId: 1,
            name: `Folder E2E A11y ${scheme} in 1.1 Smoke Matter`,
            startedBy: { id: me.id, username: me.username, name: me.name },
            state: 'running',
            cancelling: false,
            emptyFirst: false,
            total: 412,
            done: 200,
            failed: 3,
            skipped: 9,
            unreachable: 4,
            leftOut: 0,
            created: new Date(now.getTime() - 300_000).toISOString(),
            started: new Date(now.getTime() - 290_000).toISOString(),
            ended: null,
            stalledSince: null,
            failingSince: null,
            estimateSecondsLeft: 360,
          }
          const active = (route: Route) => route.fulfill({ json: { items: [job], offset: 0, limit: Number(new URL(route.request().url()).searchParams.get('limit') ?? 200), total: 1 } })
          const matched = (url: URL) => url.pathname === '/api/v1/admin/reindex' && url.searchParams.get('active') === 'true'
          await page.route(matched, active)
          try {
            // The page loaded afresh, so its first read of them is this test's answer
            await page.goto('/admin/search-index')
            await expect(heading).toBeVisible()
            const card = main().getByRole('region', { name: `Reindexing ${job.name}` })
            await expect(card).toBeVisible()
            // The bar is named, says how far in words, and is described by the line under it, which says how many were on
            // storage that couldn't be reached
            const bar = card.getByRole('progressbar', { name: 'Documents' })
            await expect(bar).toHaveAttribute('aria-valuenow', '212')
            await expect(bar).toHaveAttribute('aria-valuemax', '412')
            await expect(bar).toHaveAttribute('aria-valuetext', '212 of 412')
            await expect(bar).toHaveAccessibleDescription("212 of 412 documents · 4 on storage that couldn't be reached · about 6 minutes left · search keeps working while it runs")
            await check("a reindex's card, running")
            const cancel = card.getByRole('button', { name: `Cancel Reindexing ${job.name}` })
            await cancel.click()
            const question = page.getByRole('alertdialog', { name: `Cancel the Reindex of ${job.name}?` })
            await expect(question).toBeVisible()
            await check("a reindex's Cancel question")
            await question.getByRole('button', { name: 'Cancel', exact: true }).click()
            await expect(question).toBeHidden()
            await expect(cancel, 'the focus back on Cancel').toBeFocused()

            // The same reindex while the server's worker fails at each try: its line says so, in place of the time left
            Object.assign(job, { failingSince: new Date(now.getTime() - 120_000).toISOString() })
            await page.goto('/admin/search-index')
            await expect(card).toBeVisible()
            await expect(bar).toHaveAccessibleDescription(/^212 of 412 documents · 4 on storage that couldn't be reached · making no progress since .+, as the server can't go on with it: it tries again every 30 seconds, and Background Jobs says why$/)
            await check("a reindex's card, its worker failing")
          } finally {
            await page.unroute(matched, active)
          }
          // A page loaded with the answers the server gives again, which hold no made-up reindex, so the page never asks
          // the server for it
          await page.goto('/admin/search-index')
          await expect(heading).toBeVisible()
          await expect(main().getByRole('region', { name: new RegExp(`E2E A11y ${scheme}`) })).toHaveCount(0)
        })

        await test.step("Try Again, refused while the index can't be written, its row kept", async () => {
          // Couldn't Be Indexed holding one made-up document, whose Try Again the test answers as the server does while the
          // index can't be written (P6-R29)
          const sentence = "The search index can't be written just now. Try again later."
          const failure = {
            document: { id: 2_000_000_001, docNumber: madeUpDocNumber, fileName: `E2E A11y ${scheme}.pdf`, matter: null },
            reason: 'The file could not be parsed',
            when: new Date().toISOString(),
          }
          const failed = (route: Route) => route.fulfill({ json: { items: [failure], offset: 0, limit: 100, total: 1 } })
          const retried = (route: Route) =>
            route.fulfill({ status: 409, contentType: 'application/problem+json', json: { type: 'about:blank', title: 'Refused', status: 409, code: 'refused', detail: sentence } })
          const isFailed = (url: URL) => url.pathname === '/api/v1/admin/search-index/failed'
          const isRetry = (url: URL) => answeredHere.has(url.pathname)
          await page.route(isFailed, failed)
          await page.route(isRetry, retried)
          try {
            await page.goto('/admin/search-index')
            const list = main().getByRole('table', { name: "Couldn't Be Indexed" })
            const row = list.getByRole('row').filter({ has: page.getByRole('rowheader', { name: `# ${madeUpDocNumber}` }) })
            await expect(row).toBeVisible()
            const button = row.getByRole('button', { name: `Try Again: # ${madeUpDocNumber} ${failure.document.fileName}` })
            await button.click()
            await expect(list.getByText(sentence, { exact: true })).toBeVisible()
            // The row stays, with the focus on its button
            await expect(row).toBeVisible()
            await expect(button).toBeFocused()
            await check("Try Again refused while the index can't be written")
          } finally {
            await page.unroute(isRetry, retried)
            await page.unroute(isFailed, failed)
          }
          await page.goto('/admin/search-index')
          await expect(heading).toBeVisible()
        })

        await test.step('the heading, asked again every 5 seconds while the Indexer runs', async () => {
          // The heading's answer as the server gave it, but with the Indexer running, which it is only for a moment at a time
          const index = await page.evaluate(async () => (await (await fetch('/api/v1/admin/search-index')).json()) as { indexer: { job: Record<string, unknown> } })
          const running = (route: Route) => route.fulfill({ json: { ...index, indexer: { ...index.indexer, job: { ...index.indexer.job, running: true } } } })
          const matched = (url: URL) => url.pathname === '/api/v1/admin/search-index'
          let asked = 0
          const counted = (request: Request) => {
            if (request.method() === 'GET' && matched(new URL(request.url()))) asked++
          }
          await page.route(matched, running)
          page.on('request', counted)
          try {
            await page.goto('/admin/search-index')
            await expect(main().getByText(/^The Indexer is running/)).toBeVisible()
            const first = asked
            // Two more asks, 5 seconds apart, with nothing done on the page
            await expect.poll(() => asked - first, { message: 'the heading asked again while the Indexer runs', timeout: 20_000, intervals: [1_000] }).toBeGreaterThanOrEqual(2)
          } finally {
            page.off('request', counted)
            await page.unroute(matched, running)
          }
          await page.goto('/admin/search-index')
          await expect(heading).toBeVisible()
        })

        await test.step('the Reindex dialog on each of its choices, cancelled', async () => {
          await main().getByRole('button', { name: 'Reindex…' }).click()
          const dialog = page.getByRole('dialog', { name: 'Reindex' })
          const choice = (name: string) => dialog.getByRole('radiogroup', { name: 'What to Reindex' }).getByRole('radio', { name })
          await expect(choice('Matter')).toBeChecked()
          // With admin's Recent Matters, if they have any: a fresh stack's admin may have opened none
          await expect(dialog.getByRole('textbox', { name: 'Matter' })).toBeVisible()
          await expect(dialog.getByText('Loading…')).toHaveCount(0)
          await check('the Reindex dialog, Matter')
          await dialog.getByRole('textbox', { name: 'Matter' }).fill('Smoke Matter')
          await dialog.getByRole('option', { name: /^1\.1 – Smoke Matter/ }).click()
          // Its count once the preview has come, or, on a stack whose matter 1.1 holds no document yet, that there's nothing
          await expect(dialog.getByRole('button', { name: /^Reindex [\d,]+ Documents?$/ }).or(dialog.getByText("There's nothing here to reindex."))).toBeVisible()
          await check('the Reindex dialog, Matter chosen')

          await choice('Folder').click()
          await expect(dialog.getByText('Matter: 1.1 – Smoke Matter')).toBeVisible()
          await expect(dialog.getByRole('listbox', { name: 'Folders in 1.1 – Smoke Matter' }).getByRole('option', { name: /^Documents/ })).toBeVisible()
          await check('the Reindex dialog, Folder')

          // A doc # nothing has, which the dialog says, with nothing to reindex
          await choice('Documents').click()
          await dialog.getByRole('textbox', { name: 'Doc #s' }).fill('999999999')
          await expect(dialog.getByRole('list', { name: 'Doc #s Given' })).toContainText('No such document')
          await expect(dialog.getByRole('button', { name: 'Reindex', exact: true })).toBeDisabled()
          await check('the Reindex dialog, Documents')

          await choice('Store').click()
          await expect(dialog.getByRole('radiogroup', { name: 'Store' }).getByRole('radio', { name: /^Smoke\b/ }).first()).toBeVisible()
          await check('the Reindex dialog, Store')

          // Everything, its count and its tick box, left unticked
          await choice('Everything').click()
          await expect(dialog.getByRole('status').filter({ hasText: /^All / })).toBeVisible()
          await expect(dialog.getByRole('checkbox', { name: 'Empty the Index First' })).not.toBeChecked()
          await expect(dialog.getByRole('button', { name: 'Reindex Everything', exact: true })).toBeEnabled()
          await check('the Reindex dialog, Everything')

          await dialog.getByRole('button', { name: 'Cancel' }).click()
          await expect(dialog).toBeHidden()
          await expect(page).toHaveURL(/\/admin\/search-index$/)
        })

        await test.step("the User app's Reindex question, cancelled", async () => {
          // Matter 1.1's own folder, where its Documents folder is a row
          const matters = await page.evaluate(async () => (await (await fetch('/api/v1/search?scope=matters&q=1.1')).json()) as { items: { number: number; client: { number: number }; folderId: number }[] })
          const smoke = matters.items.find((one) => one.client.number === 1 && one.number === 1)
          expect(smoke, 'the smoke fixture has matter 1.1').toBeTruthy()
          await page.goto(`/app/folders/${smoke!.folderId}`)
          const row = main().getByRole('grid').getByRole('row', { name: /^Documents\b/ })
          await row.getByRole('rowheader').click({ button: 'right' })
          await page.getByRole('menu', { name: 'Actions for Documents' }).getByRole('menuitem', { name: 'Reindex' }).click()
          const question = page.getByRole('dialog', { name: 'Reindex' })
          // Its count once the preview has come, or, on a stack whose Documents folder holds no document yet, that there's nothing
          await expect(question.getByRole('button', { name: /^Reindex [\d,]+ Documents?$/ }).or(question.getByText("There's nothing here to reindex."))).toBeVisible()
          await check("the User app's Reindex question")
          await question.getByRole('button', { name: 'Cancel' }).click()
          await expect(question).toBeHidden()
        })

        // Back where the next test starts
        await page.goto('/admin/')
        await expect(main().getByRole('heading', { level: 1, name: 'Dashboard' })).toBeVisible()
      } finally {
        page.off('request', writes)
      }
      expect(changes, "Phase 6's screens and dialogs, only read").toEqual([])
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
      // Phase 3's, Phase 5's and Phase 6's lists at the same width, each reached from the pane, which closes as its link is
      // followed. The Errors and Search Index entries can carry their counts, and the Errors list can be empty, which it then
      // says; the Search Index's tabs are plain tables, which can be empty too
      for (const screen of ['Stores', 'Backends', 'Errors', 'Audit Trail', 'Search Index']) {
        await navigation.click()
        await page.getByRole('navigation', { name: 'Navigation' }).getByRole('link', { name: new RegExp(`^${screen}(,|$)`) }).click()
        await expect(page.getByRole('navigation', { name: 'Navigation' })).toHaveCount(0)
        await expect(main().getByRole('heading', { level: 1, name: screen })).toBeVisible()
        if (screen !== 'Errors' && screen !== 'Search Index') await expect(main().getByRole('grid', { name: screen }).getByRole('row').nth(1)).toBeVisible()
        await expect(main().getByText('Loading…')).toHaveCount(0)
        // Nothing scrolls sideways, the filters included
        expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth), `${screen} at 768 pixels`).toBe(true)
        await check(`the ${screen} list at 768 pixels`)
      }

      // The Search Index's Waiting and History tables, each in a box of its own that scrolls sideways when it's narrower
      // than its table: a region named for its table and a Tab stop then, which the arrow keys scroll, and neither when it
      // isn't. At 768 pixels, where they fit, then at 480, where they don't. Their rows are answered by this test, as the
      // stack's Indexer empties Waiting within seconds and a run's Reindexes may have none yet.
      const madeUp = (n: number) => ({ id: 2_000_000_100 + n, docNumber: 999_999_100 + n, fileName: `E2E A11y Sideways ${n}.pdf`, matter: { id: 1, number: '1.1', name: 'Smoke Matter' } })
      const at = new Date().toISOString()
      const me = await page.evaluate(async () => (await (await fetch('/api/v1/me')).json()) as { id: number; username: string; name: string })
      const reindexes = [1, 2, 3].map((n) => ({
        id: 2_000_000_200 + n,
        kind: 'folder',
        targetId: 1,
        name: `Folder E2E A11y Sideways ${n} in 1.1 Smoke Matter`,
        startedBy: { id: me.id, username: me.username, name: me.name },
        state: 'done',
        cancelling: false,
        emptyFirst: false,
        total: 3,
        done: 3,
        failed: 0,
        skipped: 0,
        unreachable: 0,
        leftOut: 0,
        created: at,
        started: at,
        ended: at,
        stalledSince: null,
        failingSince: null,
        estimateSecondsLeft: null,
      }))
      const served: [(url: URL) => boolean, (route: Route) => Promise<void>][] = [
        [(url) => url.pathname === '/api/v1/admin/search-index/waiting', (route) => route.fulfill({ json: { items: [1, 2, 3].map((n) => ({ document: madeUp(n), lastChanged: at })), offset: 0, limit: 100, total: 3 } })],
        [(url) => url.pathname === '/api/v1/admin/reindex' && url.searchParams.get('active') !== 'true', (route) => route.fulfill({ json: { items: reindexes, offset: 0, limit: 20, total: 3 } })],
        [
          (url) => url.pathname === '/api/v1/admin/search-index/history',
          (route) =>
            route.fulfill({
              json: { items: [1, 2, 3].map((n) => ({ id: 2_000_000_300 + n, when: at, event: 'indexed', reason: null, who: me.name, document: madeUp(n) })), nextCursor: null, readBackTo: null },
            }),
        ],
      ]
      for (const [url, handler] of served) await page.route(url, handler)
      const sideways = async (tabName: string, table: string, from: (tab: Locator) => Locator) => {
        const tab = main().getByRole('tab', { name: new RegExp(`^${tabName}( \\([\\d,]+\\))?$`) })
        await tab.click()
        await expect(main().getByText('Loading…')).toHaveCount(0)
        const drawn = main().getByRole('table', { name: table })
        await expect(drawn).toBeVisible()
        const box = drawn.locator('xpath=..')
        const overflows = await box.evaluate((element) => element.scrollWidth > element.clientWidth)
        if (!overflows) {
          await expect(box).not.toHaveAttribute('tabindex')
          await expect(box).not.toHaveAttribute('role')
          return `${table}: fits`
        }
        const region = main().getByRole('region', { name: `${table} (Scrolls Sideways)` })
        await expect(region).toHaveAttribute('tabindex', '0')
        await expect(region.getByRole('table', { name: table })).toBeVisible()
        // Reached by Tab from what comes before it, and scrolled by the arrow keys
        await tabTo(page, region, { from: from(tab) })
        expect(await region.evaluate((element) => element.scrollLeft)).toBe(0)
        await expect(async () => {
          await page.keyboard.press('ArrowRight')
          expect(await region.evaluate((element) => element.scrollLeft)).toBeGreaterThan(0)
        }).toPass({ timeout: 10_000 })
        await check(`the ${table} table scrolling sideways at ${page.viewportSize()?.width} pixels`)
        return `${table}: scrolls sideways`
      }
      try {
        // A page loaded afresh, so the tabs read the answers above
        await page.goto('/admin/search-index')
        await expect(main().getByRole('heading', { level: 1, name: 'Search Index' })).toBeVisible()
        for (const width of [768, 480]) {
          await page.setViewportSize({ width, height: 1024 })
          const seen = [
            await sideways('Waiting', 'Waiting', (tab) => tab),
            await sideways('History', 'Reindexes', () => main().getByRole('heading', { level: 2, name: 'Reindexes' })),
            await sideways('History', 'Index Events', () => main().getByRole('heading', { level: 2, name: 'Index Events' })),
          ]
          test.info().annotations.push({ type: `sideways at ${width} pixels`, description: seen.join('; ') })
          console.log(`sideways at ${width} pixels (${scheme}): ${seen.join('; ')}`)
          // At 480 pixels every one of them is narrower than its table, so the region and its Tab stop are always checked
          if (width === 480) expect(seen, 'each table scrolls sideways at 480 pixels').toEqual(['Waiting: scrolls sideways', 'Reindexes: scrolls sideways', 'Index Events: scrolls sideways'])
        }
      } finally {
        for (const [url, handler] of served) await page.unroute(url, handler)
      }
      await page.setViewportSize({ width: 768, height: 1024 })
      await page.goto('/admin/search-index')
      await expect(main().getByRole('heading', { level: 1, name: 'Search Index' })).toBeVisible()

      // The Reindex dialog at the same width: within the window, nothing scrolling sideways, and usable, Folder's matter
      // search shown; cancelled with nothing sent
      await main().getByRole('button', { name: 'Reindex…' }).click()
      const dialog = page.getByRole('dialog', { name: 'Reindex' })
      await expect(dialog.getByRole('textbox', { name: 'Matter' })).toBeVisible()
      const box = await dialog.boundingBox()
      expect(box, 'the dialog is drawn').not.toBeNull()
      expect(box!.x, 'the dialog within the window, on the left').toBeGreaterThanOrEqual(0)
      expect(box!.x + box!.width, 'the dialog within the window, on the right').toBeLessThanOrEqual(768)
      expect(await dialog.evaluate((element) => element.scrollWidth <= element.clientWidth), 'nothing in the dialog scrolls sideways').toBe(true)
      await check('the Reindex dialog at 768 pixels')
      await dialog.getByRole('radiogroup', { name: 'What to Reindex' }).getByRole('radio', { name: 'Folder' }).click()
      await expect(dialog.getByRole('textbox', { name: 'Matter' })).toBeVisible()
      await check('the Reindex dialog on Folder at 768 pixels')
      await dialog.getByRole('button', { name: 'Cancel' }).click()
      await expect(dialog).toBeHidden()

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
