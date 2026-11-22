// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { expect, test, type BrowserContext, type Locator, type Page } from '@playwright/test'
import { ADMIN_PASSWORD, ADMIN_USERNAME, escaped, signIn } from './helpers'

// Healdan Admin's Phase 4 end to end (admin spec 8.7 and 17.1), as the stack's admin, with a person and a group of this
// run's own: New User makes a password account and shows its temporary password once; the run's own New Group gets the
// person from its Add Member, and the group's page and the person's list each other. Reset Password shows a new
// temporary password, with which the person signs in at the sign-in page in a second browser, changes it there as the
// page asks and lands in the User app. Disable, after its question, signs them out there: that browser's next request
// is refused (401) and its page says it's signed out. Enable lets them sign in again, with the password they chose.
// Nothing else changes: not webuser, apiuser, Everyone or admin's own account. And no sign-in of any name ever fails
// with a wrong password, the run's own person's included, as five would block a username for 15 minutes: the person's
// first sign-in, with the temporary password, is the right password that has to change, which the throttle doesn't count.
// Each browser runs it, so its names carry the project's.
test.describe.configure({ mode: 'serial' })

const ADMIN = { username: ADMIN_USERNAME, password: ADMIN_PASSWORD }

/**
 * The password the person chooses at the sign-in page: one the default rules take (8 characters at least, 2 capitals and
 * 2 small letters, a digit and a symbol, no run of 4 such as `abcd` or `1234`, and no character more than 3 times), which
 * no account had before, as the person is new
 */
const CHOSEN = 'Kp9#Rv2!Tx'

/** This run's names, made in beforeAll */
let names = { username: '', first: 'E2E', last: '', person: '', group: '' }
/** The person's id, once New User has made them */
let personId = 0
/** The temporary password the reset showed, for the person's first sign-in, and nowhere else */
let temporary = ''

/** The admin's page, and the person's own browser, each kept from one test to the next */
let adminContext: BrowserContext | undefined
let admin: Page
let personContext: BrowserContext | undefined
let person: Page

const main = (page: Page) => page.getByRole('main')
const pane = (page: Page) => page.getByRole('navigation', { name: 'Navigation' })
const toast = (page: Page, text: string) => expect(page.getByText(text, { exact: true }).first()).toBeVisible()
/** A list's row, by its first column */
const row = (list: Locator, name: string) => list.getByRole('row').filter({ has: list.page().getByRole('rowheader', { name, exact: true }) })
/** What a fact of the page's says, by its term (the dt and dd of a description list) */
const fact = (page: Page, term: string) => main(page).locator('dt').filter({ hasText: new RegExp(`^${escaped(term)}$`) }).locator('xpath=following-sibling::dd[1]')
/** The status beside the person's name on their page */
const status = (page: Page) => main(page).locator('h1').locator('xpath=following-sibling::*[1]')

/** The temporary password a dialog shows, once */
async function shownPassword(dialog: Locator): Promise<string> {
  const shown = (await dialog.getByRole('group', { name: 'Temporary Password' }).innerText()).trim()
  expect(shown, 'a temporary password is shown').not.toBe('')
  await expect(dialog.getByText("It isn't shown again: once this closes, it's gone.")).toBeVisible()
  return shown
}

/** The browser's next request of its own: what the API answers the session it holds */
const meStatus = (page: Page) => page.evaluate(() => fetch('/api/v1/me').then((answer) => answer.status))

test.beforeAll(async ({ browser }, testInfo) => {
  expect(ADMIN_PASSWORD, 'ADMIN_PASSWORD is set by docker/tests/test-web.sh').not.toBe('')
  // A time and a random part, so two runs, or the two browsers, never share a name
  const stamp = `${Date.now().toString(36)}${Math.floor(Math.random() * 36 ** 4).toString(36)}`
  const run = `${testInfo.project.name}-${stamp}`
  names = { username: `e2e${testInfo.project.name.slice(0, 1)}${stamp}`, first: 'E2E', last: `Person ${run}`, person: `E2E Person ${run}`, group: `E2E Group ${run}` }
  adminContext = await browser.newContext(testInfo.project.use)
  admin = await adminContext.newPage()
  personContext = await browser.newContext(testInfo.project.use)
  person = await personContext.newPage()
})

test.afterAll(async () => {
  await personContext?.close()
  await adminContext?.close()
})

test('New User makes a password account, and shows its temporary password once', async () => {
  await admin.goto('/admin/users')
  await expect(admin).toHaveURL(/\/login\.jsp/)
  await signIn(admin, ADMIN)
  await expect(admin).toHaveURL(/\/admin\/users$/)
  await expect(main(admin).getByRole('heading', { level: 1, name: 'Users' })).toBeVisible()
  // The stack's own people are listed; the spec changes none of them
  const list = main(admin).getByRole('grid', { name: 'Users' })
  await expect(list.getByRole('row').nth(1)).toBeVisible()

  await main(admin).getByRole('button', { name: 'New User' }).click()
  const dialog = admin.getByRole('dialog', { name: 'New User' })
  await expect(dialog.getByRole('textbox', { name: 'Username' })).toBeFocused()
  await dialog.getByRole('textbox', { name: 'Username' }).fill(names.username)
  await dialog.getByRole('textbox', { name: 'First Name' }).fill(names.first)
  await dialog.getByRole('textbox', { name: 'Last Name' }).fill(names.last)
  // The language New User fills in, once the options have come
  await expect(dialog.getByRole('combobox', { name: 'Language' })).not.toHaveValue('')
  // Once the username's look-up has found nobody with it
  const create = dialog.getByRole('button', { name: 'Create User' })
  await expect(create).toBeEnabled()
  await create.click()

  // The account is made, and its temporary password shown here once
  const made = admin.getByRole('dialog', { name: `Added ${names.person}` })
  await expect(made).toBeVisible()
  await expect(made).toContainText(`Give ${names.person} this temporary password. They change it when they sign in with it.`)
  await shownPassword(made)
  await made.getByRole('button', { name: 'Done' }).click()
  await expect(made).toBeHidden()

  // Their page: a password account, which must change it at its first sign-in
  await expect(admin).toHaveURL(/\/admin\/users\/\d+$/)
  personId = Number(new URL(admin.url()).pathname.split('/').pop())
  await expect(main(admin).getByRole('heading', { level: 1, name: names.person })).toBeVisible()
  await expect(status(admin)).toHaveText('Active')
  await expect(fact(admin, 'Username')).toHaveText(names.username)
  await expect(fact(admin, 'Signs In With')).toHaveText('Password, which they must change at their next sign-in')
  await expect(fact(admin, 'Last Sign-In')).toHaveText('Never')
  // In Everyone, as every account is, and no other group yet
  await expect(main(admin).getByRole('list', { name: `Groups of ${names.person}` }).getByRole('listitem')).toHaveText([/^Everyone/])

  // And the list has them, by their name, with their username
  await main(admin).getByRole('navigation', { name: 'Breadcrumb' }).getByRole('link', { name: 'Users' }).click()
  await main(admin).getByRole('group', { name: 'Filter Users' }).getByRole('textbox', { name: 'Name, Username or Email' }).fill(names.username)
  await expect(row(list, names.person)).toBeVisible()
  await expect(row(list, names.person)).toContainText(names.username)
  await expect(row(list, names.person)).toContainText('Password')
})

test("the run's own New Group, with the person added from its members, each page listing the other", async () => {
  await pane(admin).getByRole('link', { name: 'Groups' }).click()
  await expect(main(admin).getByRole('heading', { level: 1, name: 'Groups' })).toBeVisible()
  await main(admin).getByRole('button', { name: 'New Group' }).click()
  const dialog = admin.getByRole('dialog', { name: 'New Group' })
  await dialog.getByRole('textbox', { name: 'Name' }).fill(names.group)
  await dialog.getByRole('textbox', { name: 'Description' }).fill('A group of an end-to-end run')
  const create = dialog.getByRole('button', { name: 'Create Group' })
  // Once the name's look-up has found no group with it
  await expect(create).toBeEnabled()
  await create.click()
  await toast(admin, `Created ${names.group}`)
  await expect(dialog).toBeHidden()

  // Its page: nobody in it, and no folder grants it anything
  await expect(admin).toHaveURL(/\/admin\/groups\/\d+$/)
  await expect(main(admin).getByRole('heading', { level: 1, name: names.group })).toBeVisible()
  await expect(fact(admin, 'Description')).toHaveText('A group of an end-to-end run')
  await expect(fact(admin, 'Members')).toHaveText('0')
  await expect(fact(admin, 'Folders That Grant It')).toHaveText('0')
  await expect(main(admin).getByText('Nobody is in this group.')).toBeVisible()

  await test.step('Add Member: the person, found by their username', async () => {
    await main(admin).getByRole('button', { name: 'Add Member' }).click()
    const add = admin.getByRole('dialog', { name: `Add a Member to ${names.group}` })
    const field = add.getByRole('combobox', { name: 'Person' })
    const answered = admin.waitForResponse((response) => response.url().includes('/api/v1/users?') && response.request().method() === 'GET')
    await field.fill(names.username)
    await answered
    // Offered by name, with the username beside it; the list opens by itself once the answer has come
    await admin.getByRole('option', { name: `${names.person} ${names.username}` }).click()
    const button = add.getByRole('button', { name: 'Add', exact: true })
    // Once the dialog knows they aren't in it already
    await expect(button).toBeEnabled()
    await button.click()
    await toast(admin, `Added ${names.person} to ${names.group}`)
    await expect(add).toBeHidden()
  })

  const members = main(admin).getByRole('table', { name: `Members of ${names.group}` })
  const member = members.getByRole('row').filter({ has: admin.getByRole('rowheader', { name: names.person }) })
  await expect(member).toBeVisible()
  await expect(member).toContainText(names.username)
  await expect(member).toContainText('Active')
  await expect(fact(admin, 'Members')).toHaveText('1')

  // The person's page, from their name, lists the group, which links back to it
  await member.getByRole('link', { name: names.person }).click()
  await expect(admin).toHaveURL(new RegExp(`/admin/users/${personId}$`))
  const groups = main(admin).getByRole('list', { name: `Groups of ${names.person}` })
  await expect(groups.getByRole('link', { name: names.group })).toBeVisible()
  await expect(groups.getByRole('listitem')).toHaveCount(2)
})

test('Reset Password, shown; with it the person signs in in a second browser, changes it as the page asks and lands in the app', async () => {
  // The sign-in page asks twice, and the change is a round trip of its own
  test.setTimeout(150_000)
  await expect(main(admin).getByRole('heading', { level: 1, name: names.person })).toBeVisible()

  await test.step('Reset Password asks first, then shows the temporary password once', async () => {
    await main(admin).getByRole('button', { name: 'Reset Password' }).click()
    const question = admin.getByRole('alertdialog', { name: `Reset the Password of ${names.person}?` })
    await expect(question).toContainText('They get a temporary password, which they must change at their next sign-in.')
    await question.getByRole('button', { name: 'Reset Password' }).click()
    const done = admin.getByRole('dialog', { name: `New Password for ${names.person}` })
    await expect(done).toBeVisible()
    temporary = await shownPassword(done)
    await done.getByRole('button', { name: 'Done' }).click()
    await expect(done).toBeHidden()
    await expect(fact(admin, 'Signs In With')).toHaveText('Password, which they must change at their next sign-in')
  })

  await test.step('the sign-in page takes the temporary password, then asks for a new one', async () => {
    await person.goto('/login.jsp')
    await signIn(person, { username: names.username, password: temporary })
    // LogicalDOC's window for a password that has to change
    await expect(person.getByText('Your password expired. You need to change it.')).toBeVisible()
    const window = person.locator('form').filter({ has: person.locator('input[name="newpassword"]') })
    await window.locator('input[name="password"]').fill(temporary)
    await window.locator('input[name="newpassword"]').fill(CHOSEN)
    await window.locator('input[name="newpasswordagain"]').fill(CHOSEN)
    await person.getByText('Apply', { exact: true }).click()
    await expect(person.getByText('Your password has been changed')).toBeVisible()
  })

  await test.step('signed in with the password they chose, they land in the User app', async () => {
    await person.goto('/login.jsp')
    await signIn(person, { username: names.username, password: CHOSEN })
    await expect(person).toHaveURL(/\/app\/$/)
    await expect(person.getByRole('heading', { name: 'Recent Documents' })).toBeVisible()
    expect(await meStatus(person)).toBe(200)
  })

  // Their page says they've changed it and signed in
  await admin.reload()
  await expect(fact(admin, 'Signs In With')).toHaveText(/^Password, set /)
  await expect(fact(admin, 'Last Sign-In')).not.toHaveText('Never')
})

test("Disable, after its question: the person's browser is refused at its next request, and says it's signed out", async () => {
  await main(admin).getByRole('button', { name: 'Disable', exact: true }).click()
  const question = admin.getByRole('alertdialog', { name: `Disable ${names.person}?` })
  await expect(question).toContainText("They can't sign in until their account is enabled again.")
  await question.getByRole('button', { name: 'Disable', exact: true }).click()
  await toast(admin, `Disabled ${names.person}`)
  await expect(question).toBeHidden()
  await expect(status(admin)).toHaveText('Disabled')
  await expect(main(admin).getByRole('button', { name: 'Enable', exact: true })).toBeVisible()

  // The disable closed their session: the next request their browser makes is refused
  await expect.poll(() => meStatus(person), { message: "the person's next request" }).toBe(401)
  // And the app's own next request, as coming back to the window asks again for what's on screen, says so
  await person.evaluate(() => void window.dispatchEvent(new Event('focus')))
  await expect(person.getByText("You've been signed out.")).toBeVisible()
})

test('Enable: the person signs in again, with the password they chose', async () => {
  await main(admin).getByRole('button', { name: 'Enable', exact: true }).click()
  await toast(admin, `Enabled ${names.person}`)
  await expect(status(admin)).toHaveText('Active')
  await expect(main(admin).getByRole('button', { name: 'Disable', exact: true })).toBeVisible()

  await person.goto('/login.jsp')
  await signIn(person, { username: names.username, password: CHOSEN })
  await expect(person).toHaveURL(/\/app\/$/)
  await expect(person.getByRole('heading', { name: 'Recent Documents' })).toBeVisible()
  expect(await meStatus(person)).toBe(200)

  // On the list as active again
  await pane(admin).getByRole('link', { name: 'Users' }).click()
  await main(admin).getByRole('group', { name: 'Filter Users' }).getByRole('textbox', { name: 'Name, Username or Email' }).fill(names.username)
  const list = main(admin).getByRole('grid', { name: 'Users' })
  await expect(row(list, names.person)).toContainText('Active')
  await expect(row(list, names.person)).toContainText(names.group)
})
