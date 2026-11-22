// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { act, fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { adminComponents } from '@healdan/api'
import { formatDate, formatDateTime } from '@healdan/ui'
import { expectNoAxeViolations } from '../test/axe'
import {
  adminGroup,
  adminRow,
  everyone,
  helenRow,
  jamieRow,
  me,
  peopleRoutes,
  priyaRow,
  problem,
  ronnieRow,
  sallyRow,
  trainees,
  userOf,
  users,
  type Routes,
} from '../test/fakeApi'
import { renderApp } from '../test/renderApp'

type A = adminComponents['schemas']
type Sent = { body?: unknown }

const PASSWORD = 'Tq7-mapLe-29'
const main = () => screen.getByRole('main')
const facts = () => {
  const list = within(main()).getByRole('region', { name: 'Details' }).querySelector('dl')!
  return Array.from(list.querySelectorAll('dt'), (term) => [term.textContent, term.nextElementSibling?.textContent])
}
const action = (name: string) => within(main()).queryByRole('button', { name })
const isDisabled = (button: HTMLElement | null) => button?.getAttribute('disabled') !== null
const groupsOf = (name: string) => within(within(main()).getByRole('list', { name: `Groups of ${name}` })).getAllByRole('listitem')

async function openPerson(id: number, over: Routes = {}) {
  const rendered = await renderApp(`/users/${id}`, { ...peopleRoutes(), ...over })
  await within(main()).findByRole('heading', { level: 1 })
  return rendered
}

describe("A person's page", () => {
  it('shows their details, their groups, their devices and what they hold, with the actions at its top', async () => {
    const { view } = await openPerson(priyaRow.id)
    expect(within(main()).getByRole('navigation', { name: 'Breadcrumb' }).textContent).toBe('UsersPriya Nair')
    expect(within(main()).getByRole('heading', { level: 1 }).textContent).toBe('Priya Nair')
    expect(within(main()).getByText('Active')).toBeTruthy()
    // The language by its name, once the options have come
    await waitFor(() => expect(facts()).toEqual([
      ['Username', 'priya'],
      ['Email', 'priya.nair@example.test'],
      ['Signs In With', `Password, set ${formatDate('2026-10-03T09:00:00Z')}`],
      ['Language', 'English'],
      ['Time Zone', 'Europe/London'],
      ['Account Made', formatDate('2026-10-01T09:00:00Z')],
      ['Last Sign-In', formatDateTime('2026-11-19T10:39:00Z')],
    ]))
    expect(['Edit', 'Reset Password', 'Sign Out Everywhere', 'Disable'].map((name) => isDisabled(action(name)))).toEqual([false, false, false, false])
    expect(action('Enable')).toBeNull()
    expect(action('Clear Sign-In Block')).toBeNull()
    // Everyone stays, as every account but a read-only one is in it
    expect(groupsOf('Priya Nair').map((item) => item.textContent)).toEqual(['EveryoneEveryone but a read-only account is in Everyone.', 'Trainees'])
    expect(within(main()).getByRole('button', { name: 'Remove Trainees' })).toBeTruthy()
    expect(within(main()).queryByRole('button', { name: 'Remove Everyone' })).toBeNull()
    const devices = await within(main()).findByRole('table', { name: 'Signed-In Devices of Priya Nair' })
    expect(within(devices).getAllByRole('row').slice(1).map((row) => row.textContent)).toEqual([
      `Healdan Helper on ThinkPadComputer linux-9e0bPassword${formatDateTime('2026-11-02T08:00:00Z')}${formatDateTime('2026-11-19T10:33:00Z')}${formatDate('2027-02-02T08:00:00Z')}Revoke`,
    ])
    const checkouts = await within(main()).findByRole('table', { name: 'Checked Out by Priya Nair' })
    expect(within(checkouts).getAllByRole('row').slice(1).map((row) => Array.from(row.children, (cell) => cell.textContent))).toEqual([
      ['10412', 'Witness Statement.docx', '1.3', '1001.1 – Charter Dispute', formatDateTime('2026-11-19T09:00:00Z'), 'Healdan Helper on ThinkPad', 'LibreOffice', 'Release'],
      ['10398', 'Board Minutes.xlsx', '1.0', '', '', '', '', 'Release'],
    ])
    expect(within(checkouts).getByRole('link', { name: '1001.1 – Charter Dispute' }).getAttribute('href')).toBe('/admin/matters/11')
    expect(within(main()).getByText('They own no matter.')).toBeTruthy()
    await expectNoAxeViolations(view.container)
  })

  it("offers no Reset Password to someone who signs in through a provider, and says what its group map sets again", async () => {
    const { view } = await openPerson(helenRow.id)
    expect(action('Reset Password')).toBeNull()
    expect(facts()).toContainEqual(['Signs In With', 'Authentik'])
    expect(facts()).toContainEqual(['Time Zone', 'None'])
    expect(within(main()).getByText('Helen Ashcombe signs in through Authentik: in a group its group map names, a change made here is set again at their next sign-in.')).toBeTruthy()
    // Once the groups' maps have been read
    await waitFor(() => expect(groupsOf('Helen Ashcombe').map((item) => item.textContent)).toContainEqual("Fee EarnersSet by Authentik's group map at each sign-in through it."))
    // The matters they own, in the Matters list
    const owned = within(main()).getByRole('link', { name: 'Owner of 3 matters' })
    expect(owned.getAttribute('href')).toBe(`/admin/matters?owner=${helenRow.id}`)
    await expectNoAxeViolations(view.container)
  })

  it("greys out what the server would refuse, with its sentence: here the admin account's own, which is the caller's", async () => {
    const { view } = await openPerson(adminRow.id)
    expect(isDisabled(action('Disable'))).toBe(true)
    expect(isDisabled(action('Reset Password'))).toBe(true)
    expect(isDisabled(action('Sign Out Everywhere'))).toBe(false)
    expect(within(main()).getByText("The admin account can't be disabled.")).toBeTruthy()
    expect(within(main()).getByText('Change your own password from your person menu.')).toBeTruthy()
    // It can't leave admin either, so admin has no Remove, and says why
    expect(within(main()).queryByRole('button', { name: 'Remove admin' })).toBeNull()
    expect(groupsOf('Alex Morgan')[0]?.textContent).toBe('admin Reads Every DocumentThe admin account is always in admin.')
    expect(within(main()).getByText('A member of admin: they can use Healdan Admin and read every document.')).toBeTruthy()
    await expectNoAxeViolations(view.container)
  })

  it('asks before Disable, naming what it ends, then shows the account disabled', async () => {
    let sent = 0
    const { api, view } = await openPerson(priyaRow.id, {
      'POST /v1/admin/users/7/disable': () => {
        sent += 1
        return userOf({ ...priyaRow, status: 'disabled', devices: 0 })
      },
    })
    await userEvent.click(action('Disable')!)
    const question = await screen.findByRole('alertdialog', { name: 'Disable Priya Nair?' })
    expect(question.textContent).toContain("They can't sign in until their account is enabled again.")
    expect(question.textContent).toContain('It signs them out everywhere: their 1 device is signed out, and their open sessions close.')
    await expectNoAxeViolations(view.baseElement)
    await userEvent.click(within(question).getByRole('button', { name: 'Disable' }))
    await waitFor(() => expect(screen.queryByRole('alertdialog', { name: 'Disable Priya Nair?' })).toBeNull())
    expect(sent).toBe(1)
    expect(await within(main()).findByText('Disabled')).toBeTruthy()
    expect(action('Enable')).toBeTruthy()
    expect(action('Disable')).toBeNull()
    expect(await screen.findByText('Disabled Priya Nair')).toBeTruthy()
    // The devices it signed out are asked for again
    await waitFor(() => expect(api.callsTo('GET /v1/admin/users/7/devices').length).toBeGreaterThan(1))
  })

  it("keeps Disable's question open with the server's sentence, for a change made meanwhile", async () => {
    await openPerson(priyaRow.id, { 'POST /v1/admin/users/7/disable': problem(409, 'refused', 'That would leave no enabled administrator.') })
    await userEvent.click(action('Disable')!)
    const question = await screen.findByRole('alertdialog', { name: 'Disable Priya Nair?' })
    await userEvent.click(within(question).getByRole('button', { name: 'Disable' }))
    expect((await within(question).findByRole('alert')).textContent).toBe('That would leave no enabled administrator.')
  })

  it('enables a disabled account, and clears a sign-in block, asking nothing', async () => {
    const { unmount } = (
      await openPerson(sallyRow.id, { 'POST /v1/admin/users/8/enable': () => userOf({ ...sallyRow, status: 'active' }) })
    ).view
    expect(action('Disable')).toBeNull()
    await userEvent.click(action('Enable')!)
    expect(await screen.findByText('Enabled Sally Oldham')).toBeTruthy()
    await waitFor(() => expect(action('Disable')).toBeTruthy())
    unmount()

    await openPerson(jamieRow.id, { 'POST /v1/admin/users/12/clear-block': () => userOf({ ...jamieRow, status: 'active', blockedUntil: null }) })
    expect(within(main()).getByText(`Blocked until ${formatDateTime(jamieRow.blockedUntil!)}`)).toBeTruthy()
    await userEvent.click(action('Clear Sign-In Block')!)
    expect(await screen.findByText('Cleared the sign-in block of Jamie Clarke')).toBeTruthy()
    await waitFor(() => expect(action('Clear Sign-In Block')).toBeNull())
  })

  it("asks before Reset Password, saying they're signed out everywhere, then shows the temporary password once", async () => {
    vi.stubGlobal('navigator', { ...navigator, clipboard: { writeText: () => Promise.resolve() } })
    let sent: unknown
    const { api, view } = await openPerson(priyaRow.id, {
      'POST /v1/admin/users/7/reset-password': ({ body }: Sent) => {
        sent = body
        return { temporaryPassword: PASSWORD, emailed: false } satisfies A['TemporaryPassword']
      },
    })
    await userEvent.click(action('Reset Password')!)
    const question = await screen.findByRole('alertdialog', { name: 'Reset the Password of Priya Nair?' })
    expect(question.textContent).toContain('They get a temporary password, which they must change at their next sign-in.')
    expect(question.textContent).toContain('Their open sessions close and their device tokens are revoked')
    expect(within(question).getByRole('checkbox', { name: 'Email It to Them' })).toBeTruthy()
    await expectNoAxeViolations(view.baseElement)
    await userEvent.click(within(question).getByRole('button', { name: 'Reset Password' }))
    const shown = await screen.findByRole('dialog', { name: 'New Password for Priya Nair' })
    expect(sent).toEqual({ emailPassword: false } satisfies A['PasswordReset'])
    expect(within(shown).getByRole('group', { name: 'Temporary Password' }).textContent).toBe(PASSWORD)
    expect(within(shown).getByRole('button', { name: 'Copy' })).toBeTruthy()
    // The dialog itself: React Aria's announcer still names the pending button that has gone with the question
    await expectNoAxeViolations(shown)
    // The page is asked for again, as the password has changed and the devices are signed out
    await waitFor(() => expect(api.callsTo('GET /v1/admin/users/7').length).toBeGreaterThan(1))
    await userEvent.click(within(shown).getByRole('button', { name: 'Done' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(document.body.textContent).not.toContain(PASSWORD)
    vi.unstubAllGlobals()
  })

  it('emails the temporary password when asked, and shows nothing', async () => {
    let sent: unknown
    await openPerson(priyaRow.id, {
      'POST /v1/admin/users/7/reset-password': ({ body }: Sent) => {
        sent = body
        return { temporaryPassword: null, emailed: true } satisfies A['TemporaryPassword']
      },
    })
    await userEvent.click(action('Reset Password')!)
    const question = await screen.findByRole('alertdialog', { name: 'Reset the Password of Priya Nair?' })
    await userEvent.click(within(question).getByRole('checkbox', { name: 'Email It to Them' }))
    await userEvent.click(within(question).getByRole('button', { name: 'Reset Password' }))
    const shown = await screen.findByRole('dialog', { name: 'New Password for Priya Nair' })
    expect(sent).toEqual({ emailPassword: true })
    expect(within(shown).getByText('Emailed Priya Nair a temporary password, at priya.nair@example.test. They change it when they sign in with it.')).toBeTruthy()
    expect(within(shown).queryByRole('group', { name: 'Temporary Password' })).toBeNull()
  })

  it('asks before Sign Out Everywhere, and says it signs you out here too when it is your own account', async () => {
    const { view } = await openPerson(priyaRow.id, { 'POST /v1/admin/users/7/sign-out': { sessions: 1, devices: 1 } satisfies A['SignedOut'] })
    await userEvent.click(action('Sign Out Everywhere')!)
    const question = await screen.findByRole('alertdialog', { name: 'Sign Priya Nair Out Everywhere?' })
    expect(question.textContent).not.toContain('signs you out here too')
    await userEvent.click(within(question).getByRole('button', { name: 'Sign Out Everywhere' }))
    expect(await screen.findByText('Signed Priya Nair out: 1 session closed, 1 device signed out')).toBeTruthy()
    view.unmount()

    await openPerson(me.id)
    await userEvent.click(action('Sign Out Everywhere')!)
    expect((await screen.findByRole('alertdialog', { name: 'Sign Alex Morgan Out Everywhere?' })).textContent).toContain('This is your own account, so it signs you out here too.')
  })

  it('edits the names, email, language and time zone, sending only what changed', async () => {
    let sent: unknown
    const { view } = await openPerson(priyaRow.id, {
      'PATCH /v1/admin/users/7': ({ body }: Sent) => {
        sent = body
        return userOf({ ...priyaRow, lastName: 'Nair-Lee', name: 'Priya Nair-Lee' }, { checkedOut: 2 })
      },
    })
    await userEvent.click(action('Edit')!)
    const dialog = await screen.findByRole('dialog', { name: 'Edit Priya Nair' })
    const save = within(dialog).getByRole('button', { name: 'Save' })
    // Nothing changed yet
    expect(isDisabled(save)).toBe(true)
    const last = within(dialog).getByRole('textbox', { name: 'Last Name' })
    await userEvent.clear(last)
    await userEvent.type(last, 'Nair-Lee ')
    await expectNoAxeViolations(view.baseElement)
    await userEvent.click(save)
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(sent).toEqual({ lastName: 'Nair-Lee' } satisfies A['UserChange'])
    expect(within(main()).getByRole('heading', { level: 1 }).textContent).toBe('Priya Nair-Lee')
    expect(await screen.findByText('Saved Priya Nair-Lee')).toBeTruthy()
  })

  it("sends only what was changed in it, whatever a new read of the person brings while it's open", async () => {
    let sent: unknown
    let person = users[priyaRow.id]!
    await openPerson(priyaRow.id, {
      'GET /v1/admin/users/7': () => person,
      'PATCH /v1/admin/users/7': ({ body }: Sent) => {
        sent = body
        return userOf({ ...priyaRow, firstName: 'Priyanka', name: 'Priyanka Nair-Lee', lastName: 'Nair-Lee' })
      },
    })
    await userEvent.click(action('Edit')!)
    const dialog = await screen.findByRole('dialog', { name: 'Edit Priya Nair' })
    // Another administrator changes the last name meanwhile, and the page reads it at a return to the window
    person = userOf({ ...priyaRow, lastName: 'Nair-Lee', name: 'Priya Nair-Lee' })
    await act(async () => void window.dispatchEvent(new Event('focus')))
    await waitFor(() => expect(screen.getAllByText('Priya Nair-Lee').length).toBeGreaterThan(0))
    // Nothing typed, so nothing to save: the old last name isn't sent back over theirs
    expect(isDisabled(within(dialog).getByRole('button', { name: 'Save' }))).toBe(true)
    const first = within(dialog).getByRole('textbox', { name: 'First Name' })
    await userEvent.clear(first)
    await userEvent.type(first, 'Priyanka')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(sent).toEqual({ firstName: 'Priyanka' } satisfies A['UserChange'])
  })

  it('tells two devices of the same name apart by their computer', async () => {
    await openPerson(helenRow.id)
    const devices = await within(main()).findByRole('table', { name: 'Signed-In Devices of Helen Ashcombe' })
    expect(within(devices).getAllByRole('rowheader').map((cell) => cell.textContent)).toEqual(['Healdan Helper on MacBookComputer mac-7f3a', 'Healdan Helper on Office PCComputer win-22c1'])
  })

  it('lets a name stored blank stay blank while another field changes, as the server keeps it (P4-R29, R31)', async () => {
    let sent: unknown
    const blank = userOf({ ...priyaRow, firstName: null, name: 'Nair' })
    await openPerson(priyaRow.id, {
      'GET /v1/admin/users/7': blank,
      'PATCH /v1/admin/users/7': ({ body }: Sent) => {
        sent = body
        return userOf({ ...priyaRow, firstName: null, name: 'Nair', email: 'p.nair@example.test' })
      },
    })
    await userEvent.click(action('Edit')!)
    const dialog = await screen.findByRole('dialog', { name: 'Edit Nair' })
    // Not needed, as it was blank when the dialog opened; the other name, which wasn't, still is
    expect(within(dialog).getByRole('textbox', { name: 'First Name' }).getAttribute('aria-required')).toBeNull()
    expect(within(dialog).getByRole('textbox', { name: 'Last Name' }).getAttribute('aria-required')).toBe('true')
    const email = within(dialog).getByRole('textbox', { name: 'Email' })
    await userEvent.clear(email)
    await userEvent.type(email, 'p.nair@example.test')
    const save = within(dialog).getByRole('button', { name: 'Save' })
    expect(isDisabled(save)).toBe(false)
    await userEvent.click(save)
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    // The blank name is left out, so it stays as it is
    expect(sent).toEqual({ email: 'p.nair@example.test' } satisfies A['UserChange'])
  })

  it('still needs a name that was there when the dialog opened, once it is cleared', async () => {
    await openPerson(priyaRow.id)
    await userEvent.click(action('Edit')!)
    const dialog = await screen.findByRole('dialog', { name: 'Edit Priya Nair' })
    expect(within(dialog).getByRole('textbox', { name: 'First Name' }).getAttribute('aria-required')).toBe('true')
    await userEvent.clear(within(dialog).getByRole('textbox', { name: 'First Name' }))
    await userEvent.type(within(dialog).getByRole('textbox', { name: 'Email' }), 'x')
    expect(isDisabled(within(dialog).getByRole('button', { name: 'Save' }))).toBe(true)
  })

  it("keeps a language the options don't list as a choice, as it is", async () => {
    await openPerson(priyaRow.id, { 'GET /v1/admin/users/7': userOf(priyaRow, { language: 'fr' }) })
    expect(facts()).toContainEqual(['Language', 'fr'])
    await userEvent.click(action('Edit')!)
    const dialog = await screen.findByRole('dialog', { name: 'Edit Priya Nair' })
    const language = within(dialog).getByRole('combobox', { name: 'Language' }) as HTMLSelectElement
    expect(language.value).toBe('fr')
    expect(isDisabled(within(dialog).getByRole('button', { name: 'Save' }))).toBe(true)
  })

  it("sends their groups with Save, which asks first when it adds them to admin, saying they'll read every document", async () => {
    let sent: unknown
    const { view } = await openPerson(priyaRow.id, {
      'PUT /v1/admin/users/7/groups': ({ body }: Sent) => {
        sent = body
        return userOf({ ...priyaRow, admin: true, groups: [adminGroup, everyone, trainees].map(({ id, name }) => ({ id, name })) })
      },
    })
    const save = within(main()).getByRole('button', { name: 'Save' })
    expect(isDisabled(save)).toBe(true)
    await userEvent.click(await within(main()).findByRole('option', { name: /^admin/ }))
    await waitFor(() => expect(groupsOf('Priya Nair')).toHaveLength(3))
    expect(groupsOf('Priya Nair')[2]?.textContent).toBe('admin Reads Every Document')
    await expectNoAxeViolations(view.container)
    // Save asks first, and Cancel sends nothing, the change kept
    await userEvent.click(save)
    let question = await screen.findByRole('alertdialog', { name: 'Add Priya Nair to admin?' })
    expect(question.textContent).toContain('Members of admin can use Healdan Admin and read every document')
    await expectNoAxeViolations(view.baseElement)
    await userEvent.click(within(question).getByRole('button', { name: 'Cancel' }))
    await waitFor(() => expect(screen.queryByRole('alertdialog', { name: 'Add Priya Nair to admin?' })).toBeNull())
    expect(sent).toBeUndefined()
    expect(groupsOf('Priya Nair')).toHaveLength(3)
    await userEvent.click(save)
    question = await screen.findByRole('alertdialog', { name: 'Add Priya Nair to admin?' })
    await userEvent.click(within(question).getByRole('button', { name: 'Add to admin' }))
    expect(await screen.findByText('Saved the groups of Priya Nair')).toBeTruthy()
    expect(sent).toEqual({ groupIds: [everyone.id, trainees.id, adminGroup.id] } satisfies A['UserGroups'])
    expect(within(main()).getByText('A member of admin: they can use Healdan Admin and read every document.')).toBeTruthy()
  })

  it("keeps a read-only account in guest, and offers it no group", async () => {
    const { view } = await openPerson(ronnieRow.id)
    expect(groupsOf('Ronnie Hale').map((item) => item.textContent)).toEqual(['guestA read-only account is always in guest.'])
    expect(within(main()).queryByRole('textbox', { name: 'Add a Group' })).toBeNull()
    expect(within(main()).getByText('A read-only account is in no group but its own and guest.')).toBeTruthy()
    expect(facts()).toContainEqual(['Signs In With', 'Password, which they must change at their next sign-in'])
    expect(facts()).toContainEqual(['Last Sign-In', 'Never'])
    await expectNoAxeViolations(view.container)
  })

  it('asks before signing out one of their devices', async () => {
    const { api } = await openPerson(helenRow.id, { 'DELETE /v1/admin/users/5/devices/501': undefined })
    const devices = await within(main()).findByRole('table', { name: 'Signed-In Devices of Helen Ashcombe' })
    await userEvent.click(within(devices).getByRole('button', { name: 'Revoke Healdan Helper on MacBook' }))
    const question = await screen.findByRole('alertdialog', { name: 'Revoke Healdan Helper on MacBook?' })
    expect(question.textContent).toContain("Helen Ashcombe's other devices stay signed in.")
    await userEvent.click(within(question).getByRole('button', { name: 'Revoke' }))
    expect(await screen.findByText('Revoked Healdan Helper on MacBook')).toBeTruthy()
    expect(api.callsTo('DELETE /v1/admin/users/5/devices/501')).toHaveLength(1)
    await waitFor(() => expect(api.callsTo('GET /v1/admin/users/5/devices').length).toBeGreaterThan(1))
  })

  it("asks before releasing one of their check-outs, through the public route, naming the computer that holds it", async () => {
    const { api } = await openPerson(priyaRow.id, { 'DELETE /v1/documents/10412/checkout': {} })
    const checkouts = await within(main()).findByRole('table', { name: 'Checked Out by Priya Nair' })
    await userEvent.click(within(checkouts).getByRole('button', { name: 'Release Witness Statement.docx' }))
    const question = await screen.findByRole('alertdialog', { name: 'Release Witness Statement.docx?' })
    expect(question.textContent).toContain("What they haven't saved to Healdan from Healdan Helper on ThinkPad is lost")
    await userEvent.click(within(question).getByRole('button', { name: 'Release' }))
    expect(await screen.findByText('Released Witness Statement.docx')).toBeTruthy()
    expect(api.callsTo('DELETE /v1/documents/10412/checkout')).toHaveLength(1)
    await waitFor(() => expect(api.callsTo('GET /v1/admin/users/7/checkouts').length).toBeGreaterThan(1))
  })

  it('says so when the address names nobody', async () => {
    const { view } = await renderApp('/users/999', peopleRoutes())
    expect(await within(main()).findByRole('heading', { level: 1, name: 'Person Not Found' })).toBeTruthy()
    expect(within(main()).getByRole('link', { name: 'Go to Users' }).getAttribute('href')).toBe('/admin/users')
    view.unmount()
    await renderApp('/users/x7', peopleRoutes())
    expect(await within(main()).findByRole('heading', { level: 1, name: 'Person Not Found' })).toBeTruthy()
  })

  it("keeps the page, and a dialog open on it, while a new read fails, and says it couldn't refresh (P4-R15)", async () => {
    let failing = false
    await openPerson(priyaRow.id, { 'GET /v1/admin/users/7': () => (failing ? problem(500, 'server-error') : users[priyaRow.id]) })
    await userEvent.click(action('Edit')!)
    const dialog = await screen.findByRole('dialog', { name: 'Edit Priya Nair' })
    fireEvent.change(within(dialog).getByRole('textbox', { name: 'First Name' }), { target: { value: 'Priyanka' } })
    failing = true
    await act(async () => void window.dispatchEvent(new Event('focus')))
    // The page stays behind the dialog, which a screen reader is kept in
    expect(await screen.findByText("Couldn't refresh this, so it shows what was read last.")).toBeTruthy()
    expect(screen.getByRole('dialog', { name: 'Edit Priya Nair' })).toBe(dialog)
    expect((within(dialog).getByRole('textbox', { name: 'First Name' }) as HTMLInputElement).value).toBe('Priyanka')
  })
})
