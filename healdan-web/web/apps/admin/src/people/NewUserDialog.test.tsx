// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, type adminComponents } from '@healdan/api'
import { createQueryClient, createSignedOut, MessagesProvider, ServicesProvider } from '@healdan/shell'
import { ToastRegion } from '@healdan/ui'
import { TYPING_DELAY_MS } from '../pickers/typing'
import { problemLookup } from '../strings'
import { expectNoAxeViolations } from '../test/axe'
import { adminGroup, authentik, fakeApi, partners, peopleRoutes, problem, userOf, userOptions, userRow, type Routes } from '../test/fakeApi'
import { NewUserDialog } from './NewUserDialog'

type A = adminComponents['schemas']
type Sent = { body?: unknown }

const PASSWORD = 'Tq7-mapLe-29'
const ann = userOf(userRow({ id: 40, username: 'ann', name: 'Ann Lee', firstName: 'Ann', lastName: 'Lee', email: 'ann@example.test', groups: [], devices: 0, lastSignIn: null }), {
  mustChangePassword: true,
})

/** Long enough for the typing to pause and the answer to come */
const pause = () => act(() => new Promise((resolve) => setTimeout(resolve, TYPING_DELAY_MS + 100)))

function renderDialog(routes: Routes = {}) {
  const api = fakeApi({ ...peopleRoutes(), ...routes })
  const queryClient = createQueryClient({ retry: false })
  const onClose = vi.fn()
  const onCreated = vi.fn<(user: A['User']) => void>()
  const view = render(
    <ServicesProvider api={api} signedOut={createSignedOut()}>
      <QueryClientProvider client={queryClient}>
        <MessagesProvider lookup={problemLookup}>
          <NewUserDialog onCreated={onCreated} onClose={onClose} />
          <ToastRegion />
        </MessagesProvider>
      </QueryClientProvider>
    </ServicesProvider>,
  )
  return { api, queryClient, onClose, onCreated, view }
}

const dialog = () => screen.getByRole('dialog', { name: 'New User' })
const field = (name: string) => within(dialog()).getByRole('textbox', { name }) as HTMLInputElement
const create = () => within(dialog()).getByRole('button', { name: 'Create User' })
const isDisabled = (button: HTMLElement) => button.getAttribute('disabled') !== null

async function fill({ username = 'ann', email = '' }: { username?: string; email?: string } = {}) {
  await waitFor(() => expect((within(dialog()).getByRole('combobox', { name: 'Language' }) as HTMLSelectElement).value).toBe('en'))
  await userEvent.type(field('Username'), username)
  await userEvent.type(field('First Name'), ' Ann ')
  await userEvent.type(field('Last Name'), 'Lee')
  if (email !== '') await userEvent.type(field('Email'), email)
  await pause()
}

afterEach(() => vi.unstubAllGlobals())

describe('New User', () => {
  it('makes a password account, then shows its temporary password once, with Copy, gone on close', async () => {
    let sent: unknown
    const writeText = vi.fn(() => Promise.resolve())
    vi.stubGlobal('navigator', { ...navigator, clipboard: { writeText } })
    const { onClose, onCreated, queryClient, view } = renderDialog({
      'POST /v1/admin/users': ({ body }: Sent) => {
        sent = body
        return { user: ann, temporaryPassword: PASSWORD, emailed: false, notEmailed: 'not-asked' } satisfies A['CreatedUser']
      },
    })
    expect(document.activeElement).toBe(field('Username'))
    await fill()
    // The language New User fills in, of those offered
    expect(within(dialog()).getByRole('combobox', { name: 'Language' }).textContent).toContain('German')
    fireEvent.change(within(dialog()).getByRole('combobox', { name: 'Language' }), { target: { value: 'de' } })
    // The time zone is found as it's typed, from those the server knows
    await userEvent.type(within(dialog()).getByRole('combobox', { name: 'Time Zone' }), 'berl')
    await userEvent.click(await screen.findByRole('option', { name: 'Europe/Berlin' }))
    await userEvent.click(await within(dialog()).findByRole('option', { name: 'Partners' }))
    expect(within(within(dialog()).getByRole('list', { name: 'Groups' })).getAllByRole('listitem').map((item) => item.firstChild?.textContent)).toEqual(['Partners'])
    expect(within(dialog()).getByText("They're in Everyone too, as every account is.")).toBeTruthy()
    await expectNoAxeViolations(view.baseElement)

    await userEvent.click(create())
    const done = await screen.findByRole('dialog', { name: 'Added Ann Lee' })
    expect(sent).toEqual({ username: 'ann', firstName: 'Ann', lastName: 'Lee', email: null, language: 'de', timeZone: 'Europe/Berlin', groupIds: [partners.id], emailPassword: false } satisfies A['NewUser'])
    expect(within(done).getByText('Give Ann Lee this temporary password. They change it when they sign in with it.')).toBeTruthy()
    expect(within(done).getByRole('group', { name: 'Temporary Password' }).textContent).toBe(PASSWORD)
    expect(within(done).getByText("It isn't shown again: once this closes, it's gone.")).toBeTruthy()
    await userEvent.click(within(done).getByRole('button', { name: 'Copy' }))
    expect(writeText).toHaveBeenCalledWith(PASSWORD)
    expect(await screen.findByText('Copied the temporary password')).toBeTruthy()
    await expectNoAxeViolations(view.baseElement)
    // Only in the dialog: never in what the app keeps
    expect(JSON.stringify(queryClient.getQueryCache().getAll().map((query) => query.state.data))).not.toContain(PASSWORD)

    await userEvent.click(within(done).getByRole('button', { name: 'Done' }))
    expect(onCreated).toHaveBeenCalledWith(ann)
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it("says so when the browser won't copy, and the password stays to be written down", async () => {
    vi.stubGlobal('navigator', { ...navigator, clipboard: { writeText: () => Promise.reject(new Error('Not allowed')) } })
    renderDialog({ 'POST /v1/admin/users': { user: ann, temporaryPassword: PASSWORD, emailed: false, notEmailed: 'not-asked' } satisfies A['CreatedUser'] })
    await fill()
    await userEvent.click(create())
    const done = await screen.findByRole('dialog', { name: 'Added Ann Lee' })
    await userEvent.click(within(done).getByRole('button', { name: 'Copy' }))
    expect((await within(done).findByRole('alert')).textContent).toBe("Couldn't copy the temporary password. Select it and copy it, or write it down.")
    expect(within(done).getByRole('group', { name: 'Temporary Password' }).textContent).toBe(PASSWORD)
  })

  it('offers to email the password once an email is typed and outgoing email is set up, and says it went', async () => {
    let sent: unknown
    renderDialog({
      'POST /v1/admin/users': ({ body }: Sent) => {
        sent = body
        return { user: ann, temporaryPassword: null, emailed: true, notEmailed: null } satisfies A['CreatedUser']
      },
    })
    await fill()
    expect(within(dialog()).queryByRole('checkbox', { name: 'Email It to Them' })).toBeNull()
    expect(within(dialog()).getByText('With no email address, the temporary password is shown here once, for you to give them.')).toBeTruthy()
    await userEvent.type(field('Email'), 'ann@example.test')
    await userEvent.click(within(dialog()).getByRole('checkbox', { name: 'Email It to Them' }))
    await userEvent.click(create())
    const done = await screen.findByRole('dialog', { name: 'Added Ann Lee' })
    expect((sent as A['NewUser']).emailPassword).toBe(true)
    expect((sent as A['NewUser']).email).toBe('ann@example.test')
    expect(within(done).getByText('Emailed Ann Lee a temporary password, at ann@example.test. They change it when they sign in with it.')).toBeTruthy()
    expect(within(done).queryByRole('group', { name: 'Temporary Password' })).toBeNull()
    expect(within(done).queryByRole('button', { name: 'Copy' })).toBeNull()
  })

  it("shows the password, and why, when it was to be emailed and couldn't go", async () => {
    renderDialog({ 'POST /v1/admin/users': { user: ann, temporaryPassword: PASSWORD, emailed: false, notEmailed: 'not-taken' } satisfies A['CreatedUser'] })
    await fill({ email: 'ann@example.test' })
    await userEvent.click(within(dialog()).getByRole('checkbox', { name: 'Email It to Them' }))
    await userEvent.click(create())
    const done = await screen.findByRole('dialog', { name: 'Added Ann Lee' })
    expect(within(done).getByText("Couldn't email the temporary password: the mail server didn't take it. Give it to Ann Lee yourself.")).toBeTruthy()
    expect(within(done).getByRole('group', { name: 'Temporary Password' }).textContent).toBe(PASSWORD)
  })

  it("never says it was emailed when it wasn't, even with no password to show", async () => {
    renderDialog({ 'POST /v1/admin/users': { user: ann, temporaryPassword: null, emailed: false, notEmailed: 'not-taken' } satisfies A['CreatedUser'] })
    await fill({ email: 'ann@example.test' })
    await userEvent.click(within(dialog()).getByRole('checkbox', { name: 'Email It to Them' }))
    await userEvent.click(create())
    const done = await screen.findByRole('dialog', { name: 'Added Ann Lee' })
    expect(done.textContent).not.toContain('Emailed')
    expect(within(done).getByText("Healdan gave no temporary password to show. Use Reset Password on Ann Lee's page to make another.")).toBeTruthy()
    expect(within(done).queryByRole('group', { name: 'Temporary Password' })).toBeNull()
  })

  it("doesn't offer to email it while outgoing email isn't set up", async () => {
    renderDialog({ 'GET /v1/admin/users/options': userOptions({ outgoingEmail: false }) })
    await fill({ email: 'ann@example.test' })
    expect(within(dialog()).queryByRole('checkbox', { name: 'Email It to Them' })).toBeNull()
    expect(within(dialog()).getByText("Outgoing email isn't set up, so the temporary password is shown here once, for you to give them.")).toBeTruthy()
  })

  it('says, while a sign-in provider is on, that people who sign in through it needn\'t be added', async () => {
    const { view } = renderDialog({ 'GET /v1/admin/users/options': userOptions({ providers: [authentik] }) })
    expect(
      await within(dialog()).findByText(
        "People who sign in through Authentik needn't be added here: their account is made at their first sign-in, with the groups the provider's group map gives them. Where a provider links accounts by email, an account made here with the same email is linked to theirs at that sign-in.",
      ),
    ).toBeTruthy()
    await expectNoAxeViolations(view.baseElement)
  })

  it('says what the server would refuse before Create: a username it can\'t keep, or that someone has, and an email that isn\'t one', async () => {
    renderDialog()
    await fill({ username: 'ann>' })
    expect(within(dialog()).getByText("A username can't hold > or a control character.")).toBeTruthy()
    expect(isDisabled(create())).toBe(true)
    await userEvent.clear(field('Username'))
    // Priya's, in another case
    await userEvent.type(field('Username'), 'PRIYA')
    await pause()
    expect(await within(dialog()).findByText('Priya Nair has this username.')).toBeTruthy()
    expect(isDisabled(create())).toBe(true)
    await userEvent.clear(field('Username'))
    await userEvent.type(field('Username'), 'ann')
    await userEvent.type(field('Email'), 'ann at example')
    expect(within(dialog()).getByText("That isn't an email address.")).toBeTruthy()
    expect(isDisabled(create())).toBe(true)
  })

  it('takes an email the server takes, such as one with no dot after its @', async () => {
    let sent: unknown
    renderDialog({
      'POST /v1/admin/users': ({ body }: Sent) => {
        sent = body
        return { user: ann, temporaryPassword: PASSWORD, emailed: false, notEmailed: 'not-asked' } satisfies A['CreatedUser']
      },
    })
    await fill({ email: 'ann@intranet' })
    expect(within(dialog()).queryByText("That isn't an email address.")).toBeNull()
    expect(isDisabled(create())).toBe(false)
    await userEvent.click(create())
    await screen.findByRole('dialog', { name: 'Added Ann Lee' })
    expect((sent as A['NewUser']).email).toBe('ann@intranet')
  })

  it("says so, with Try Again, when what it offers can't be read, and fills in the language once it can", async () => {
    const UNREAD = "Couldn't read the languages and time zones to choose from, so the account can't be made yet."
    // Each read fails, until the test gives one an answer of its own
    let next: () => unknown = () => problem(500, 'server-error')
    const { api, view } = renderDialog({ 'GET /v1/admin/users/options': () => next() })
    expect(await within(dialog()).findByText(UNREAD)).toBeTruthy()
    await userEvent.type(field('Username'), 'ann')
    await userEvent.type(field('First Name'), 'Ann')
    await userEvent.type(field('Last Name'), 'Lee')
    await pause()
    expect(isDisabled(create())).toBe(true)
    await expectNoAxeViolations(view.baseElement)
    const language = within(dialog()).getByRole('combobox', { name: 'Language' }) as HTMLSelectElement
    const tryAgain = () => within(dialog()).getByRole('button', { name: /^Try Again/ })

    // A second read that fails too: the note stays, and the focus with Try Again
    await userEvent.click(tryAgain())
    await waitFor(() => expect(api.callsTo('GET /v1/admin/users/options')).toHaveLength(2))
    await waitFor(() => expect(tryAgain().hasAttribute('data-pending')).toBe(false))
    expect(within(dialog()).getByText(UNREAD)).toBeTruthy()
    expect(document.activeElement).toBe(tryAgain())

    // While a read is on its way, the note and Try Again stay, pending, and the focus stays on it: Language has no choices yet
    let answer: (options: A['UserOptions']) => void = () => {}
    next = () => new Promise((resolve) => (answer = resolve))
    await userEvent.click(tryAgain())
    await waitFor(() => expect(tryAgain().getAttribute('data-pending')).toBe('true'))
    expect(within(dialog()).getByText(UNREAD)).toBeTruthy()
    expect(document.activeElement).toBe(tryAgain())
    expect(language.options).toHaveLength(1)

    // Once they come, Try Again goes with the note, and the focus goes to the menu they filled
    await act(async () => answer(userOptions()))
    await waitFor(() => expect(language.value).toBe('en'))
    expect(within(dialog()).queryByText(UNREAD)).toBeNull()
    expect(api.callsTo('GET /v1/admin/users/options')).toHaveLength(3)
    expect(document.activeElement).toBe(language)
    expect(isDisabled(create())).toBe(false)
  })

  it('closes the temporary password only from Done, as it is its only showing: Esc and a click outside leave it (P4-R38)', async () => {
    const { onClose, onCreated } = renderDialog({ 'POST /v1/admin/users': { user: ann, temporaryPassword: PASSWORD, emailed: false, notEmailed: 'not-asked' } satisfies A['CreatedUser'] })
    await fill()
    await userEvent.click(create())
    const done = await screen.findByRole('dialog', { name: 'Added Ann Lee' })
    await userEvent.keyboard('{Escape}')
    await userEvent.click(document.body)
    expect(screen.getByRole('dialog', { name: 'Added Ann Lee' })).toBe(done)
    expect(within(done).getByRole('group', { name: 'Temporary Password' }).textContent).toBe(PASSWORD)
    expect(onClose).not.toHaveBeenCalled()
    await userEvent.click(within(done).getByRole('button', { name: 'Done' }))
    expect(onCreated).toHaveBeenCalledWith(ann)
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it("says the server's refusal of a field beside it, and the rest above the buttons", async () => {
    let answer: unknown = new ApiError(422, 'validation', 'x', { status: 422, code: 'validation', fields: [{ field: 'username', code: 'taken', message: 'x' }] })
    renderDialog({ 'POST /v1/admin/users': () => answer })
    await fill()
    await userEvent.click(create())
    expect(await within(dialog()).findByText('Another account has this username.')).toBeTruthy()
    answer = problem(409, 'refused', 'Trainees no longer exists.')
    await userEvent.type(field('Username'), 'e')
    await pause()
    await userEvent.click(create())
    expect((await within(dialog()).findByRole('alert')).textContent).toBe('Trainees no longer exists.')
  })

  it('asks before Create adds someone to admin, saying they will read every document', async () => {
    let sent: unknown
    const { view } = renderDialog({
      'POST /v1/admin/users': ({ body }: Sent) => {
        sent = body
        return { user: ann, temporaryPassword: PASSWORD, emailed: false, notEmailed: 'not-asked' } satisfies A['CreatedUser']
      },
    })
    await fill()
    await userEvent.click(await within(dialog()).findByRole('option', { name: /^admin/ }))
    expect(within(within(dialog()).getByRole('list', { name: 'Groups' })).getAllByRole('listitem').map((item) => item.firstChild?.textContent)).toEqual(['admin Reads Every Document'])
    await userEvent.click(create())
    const question = await screen.findByRole('alertdialog', { name: 'Add Ann Lee to admin?' })
    expect(question.textContent).toContain('Members of admin can use Healdan Admin and read every document')
    await expectNoAxeViolations(view.baseElement)
    await userEvent.click(within(question).getByRole('button', { name: 'Cancel' }))
    await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull())
    expect(sent).toBeUndefined()
    await userEvent.click(create())
    await userEvent.click(within(await screen.findByRole('alertdialog', { name: 'Add Ann Lee to admin?' })).getByRole('button', { name: 'Add to admin' }))
    await screen.findByRole('dialog', { name: 'Added Ann Lee' })
    expect((sent as A['NewUser']).groupIds).toEqual([adminGroup.id])
  })

  it("holds while Create is on its way, as the account would still be made", async () => {
    let finish: (value: unknown) => void = () => {}
    const { onClose } = renderDialog({ 'POST /v1/admin/users': () => new Promise((resolve) => (finish = resolve)) })
    await fill()
    await userEvent.click(create())
    await waitFor(() => expect(isDisabled(within(dialog()).getByRole('button', { name: 'Cancel' }))).toBe(true))
    await userEvent.keyboard('{Escape}')
    expect(screen.getByRole('dialog', { name: 'New User' })).toBeTruthy()
    expect(onClose).not.toHaveBeenCalled()
    await act(async () => finish({ user: ann, temporaryPassword: PASSWORD, emailed: false, notEmailed: 'not-asked' }))
    expect(await screen.findByRole('dialog', { name: 'Added Ann Lee' })).toBeTruthy()
  })
})
