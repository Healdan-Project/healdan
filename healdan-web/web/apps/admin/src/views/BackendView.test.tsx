// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import type { adminComponents } from '@healdan/api'
import { formatDateTime, formatSize } from '@healdan/ui'
import { expectNoAxeViolations } from '../test/axe'
import { archiveBackend, nasBackend, originalBackend, problem, RECOVERY_KEY, wasabiBackend } from '../test/fakeApi'
import { renderApp, type Rendered } from '../test/renderApp'

type A = adminComponents['schemas']

const main = () => screen.getByRole('main')
const actions = () => within(main()).getAllByRole('button').map((one) => one.textContent)
/** The page's facts, each term with what it says */
const facts = () => within(main()).getAllByRole('term').map((term) => [term.textContent, term.nextElementSibling?.textContent])

/** A change the server answers only when the test says, as a slow one would be */
function slowAnswer<T>() {
  let answer!: (value: T) => void
  const answered = new Promise<T>((resolve) => {
    answer = resolve
  })
  return { answered, answer }
}

/** Whether the text appears anywhere the app keeps things: the query cache, the address and the browser's storage */
function keptAnywhere(rendered: Rendered, value: string): boolean {
  const cached = JSON.stringify(rendered.queryClient.getQueryCache().getAll().map((query) => [query.queryKey, query.state.data]))
  const stored = JSON.stringify({ ...window.localStorage }) + JSON.stringify({ ...window.sessionStorage }) + JSON.stringify(window.history.state)
  return cached.includes(value) || rendered.router.state.location.href.includes(value) || stored.includes(value)
}

async function page(id: number, routes: Record<string, unknown> = {}) {
  const rendered = await renderApp(`/backends/${id}`, routes)
  const name = [originalBackend, wasabiBackend, archiveBackend, nasBackend].find((one) => one.id === id)?.name ?? ''
  await screen.findByRole('heading', { level: 1, name })
  return rendered
}

describe("A backend's page", () => {
  it('shows an encrypted S3 backend: its status, facts, the Stores on it and its actions, with the way back to Backends', async () => {
    const { view } = await page(2)
    expect(within(main()).getByText('OK')).toBeTruthy()
    expect(facts()).toEqual([
      ['Kind', 'S3'],
      ['Where', 'ashcombe-reeve/healdan/ at https://s3.eu-central-1.wasabisys.com'],
      ['Holds', `3,902 files · ${formatSize(wasabiBackend.bytes)}`],
      ['Encrypted', 'Yes'],
      ['Recovery Key', 'Confirmed'],
      ['Key Last Shown', `${formatDateTime('2026-10-02T09:15:00Z')} to sam`],
      ['Stores', 'Client Work'],
    ])
    expect(actions()).toEqual(['Test', 'Edit', 'Show Recovery Key', 'Enter Recovery Key', 'Key History', 'Remove'])
    const crumbs = within(main()).getByRole('navigation', { name: 'Breadcrumb' })
    expect(within(crumbs).getByRole('link', { name: 'Backends' }).getAttribute('href')).toBe('/admin/backends')
    await expectNoAxeViolations(view.container)
  })

  it("says an encrypted backend whose key isn't confirmed can't be given to a Store yet, and offers to confirm it", async () => {
    const { view } = await page(4)
    expect(within(main()).getByText(`Failing since ${formatDateTime('2026-11-19T09:30:00Z')}: The marker of this backend is missing.`)).toBeTruthy()
    expect(within(main()).getByText("Its recovery key isn't confirmed, so no Store can use it yet.")).toBeTruthy()
    expect(facts()).toContainEqual(['Recovery Key', 'Not Confirmed'])
    expect(facts()).toContainEqual(['Key Last Shown', 'Never'])
    expect(facts()).toContainEqual(['Stores', 'None'])
    expect(actions()).toEqual(['Test', 'Edit', 'Show Recovery Key', 'Confirm Recovery Key', 'Enter Recovery Key', 'Key History', 'Remove'])
    await expectNoAxeViolations(view.container)
  })

  it("shows Original, which can be renamed but not removed, with no recovery key", async () => {
    await page(1)
    expect(facts()).toEqual([
      ['Kind', 'Local Folder'],
      ['Where', '/opt/logicaldoc/repository/docs'],
      ['Holds', `812 files · ${formatSize(originalBackend.bytes)}`],
      ['Encrypted', 'No'],
      ['Stores', 'Firm'],
    ])
    expect(actions()).toEqual(['Test', 'Edit'])
    expect(within(main()).getByText(/it can't be removed: it keeps the files of every Store that has no other backend/)).toBeTruthy()
  })

  it('tests the saved backend in full, says what it found, and asks again for its status', async () => {
    let problems: string[] = ['The key file of this backend is missing.']
    const { api } = await page(2, { 'POST /v1/admin/backends/2/test': () => ({ problems }) satisfies A['BackendTestResult'] })
    await userEvent.click(within(main()).getByRole('button', { name: 'Test' }))
    const found = await within(main()).findByRole('status')
    await waitFor(() => expect(found.textContent).toContain('Test found one problem:'))
    expect(within(found).getByRole('listitem').textContent).toBe('The key file of this backend is missing.')
    await waitFor(() => expect(api.callsTo('GET /v1/admin/backends/2')).toHaveLength(2))
    problems = []
    await userEvent.click(within(main()).getByRole('button', { name: 'Test' }))
    await waitFor(() => expect(within(main()).getByRole('status').textContent).toBe('Test passed.'))
  })

  it("says so when there's no such backend, or storage backends are off", async () => {
    const { view } = await renderApp('/backends/99')
    expect(await screen.findByRole('heading', { level: 1, name: 'Backend Not Found' })).toBeTruthy()
    expect(within(main()).getByRole('link', { name: 'Go to Backends' }).getAttribute('href')).toBe('/admin/backends')
    await expectNoAxeViolations(view.container)
  })

  it("says it can't be shown on a server without Healdan's storage module", async () => {
    await renderApp('/backends/2', { 'GET /v1/admin/backends/2': () => problem(503, 'storage-unavailable') })
    expect(await within(main()).findByText("The Backends screen can't be shown on this server, as it doesn't have Healdan's storage module.")).toBeTruthy()
    expect(within(main()).queryByRole('button')).toBeNull()
  })
})

describe('Show Recovery Key', () => {
  it('asks first, saying the showing is recorded, and shows the key only in its dialog', async () => {
    const rendered = await page(2, { 'POST /v1/admin/backends/2/recovery-key/show': { recoveryKey: RECOVERY_KEY } satisfies A['RecoveryKey'] })
    await userEvent.click(within(main()).getByRole('button', { name: 'Show Recovery Key' }))
    const ask = await screen.findByRole('alertdialog', { name: 'Show the Recovery Key of Wasabi EU?' })
    expect(within(ask).getByText(/Each showing is recorded, with who and when/)).toBeTruthy()
    expect(rendered.api.callsTo('POST /v1/admin/backends/2/recovery-key/show')).toEqual([])
    await expectNoAxeViolations(rendered.view.container)

    await userEvent.click(within(ask).getByRole('button', { name: 'Show Recovery Key' }))
    const shown = await screen.findByRole('dialog', { name: 'Recovery Key of Wasabi EU' })
    // The question's dialog went as this one came: the focus is on its heading, with no field to type in
    await waitFor(() => expect(document.activeElement).toBe(within(shown).getByRole('heading', { name: 'Recovery Key of Wasabi EU' })))
    expect(within(shown).getByRole('group', { name: 'Recovery Key' }).textContent?.replace(/\s+/g, ' ').trim()).toBe(RECOVERY_KEY)
    // Confirmed already, so nothing is asked back
    expect(within(shown).queryByRole('textbox', { name: 'Last Group' })).toBeNull()
    expect(keptAnywhere(rendered, RECOVERY_KEY)).toBe(false)
    await expectNoAxeViolations(rendered.view.container)
    await userEvent.click(within(shown).getByRole('button', { name: 'Close' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Recovery Key of Wasabi EU' })).toBeNull())
    // Its last showing is asked for again
    await waitFor(() => expect(rendered.api.callsTo('GET /v1/admin/backends/2').length).toBeGreaterThanOrEqual(2))
    expect(keptAnywhere(rendered, RECOVERY_KEY)).toBe(false)
  })

  it("asks for an unconfirmed key's last group as it shows it", async () => {
    const { api } = await page(4, {
      'POST /v1/admin/backends/4/recovery-key/show': { recoveryKey: RECOVERY_KEY } satisfies A['RecoveryKey'],
      'POST /v1/admin/backends/4/recovery-key/confirm': { ...nasBackend, keyConfirmed: true, offered: true },
    })
    await userEvent.click(within(main()).getByRole('button', { name: 'Show Recovery Key' }))
    await userEvent.click(within(await screen.findByRole('alertdialog')).getByRole('button', { name: 'Show Recovery Key' }))
    const shown = await screen.findByRole('dialog', { name: 'Recovery Key of Office NAS' })
    // The question's dialog went as this one came: the focus is on the field it asks for
    await waitFor(() => expect(document.activeElement).toBe(within(shown).getByRole('textbox', { name: 'Last Group' })))
    await userEvent.type(within(shown).getByRole('textbox', { name: 'Last Group' }), '4jp8')
    await userEvent.click(within(shown).getByRole('button', { name: 'Confirm' }))
    await waitFor(() => expect(api.callsTo('POST /v1/admin/backends/4/recovery-key/confirm')).toHaveLength(1))
    expect(api.callsTo('POST /v1/admin/backends/4/recovery-key/confirm')[0]!.body).toEqual({ lastGroup: '4jp8' })
    // The page shows the answer at once
    await waitFor(() => expect(facts()).toContainEqual(['Recovery Key', 'Confirmed']))
    expect(actions()).not.toContain('Confirm Recovery Key')
  })

  it("says the rule's sentence when the key can't be shown", async () => {
    await page(2, { 'POST /v1/admin/backends/2/recovery-key/show': () => problem(409, 'refused', 'The key file of Wasabi EU is missing: enter its recovery key to put it back.') })
    await userEvent.click(within(main()).getByRole('button', { name: 'Show Recovery Key' }))
    const ask = await screen.findByRole('alertdialog')
    await userEvent.click(within(ask).getByRole('button', { name: 'Show Recovery Key' }))
    expect((await within(ask).findByRole('alert')).textContent).toBe('The key file of Wasabi EU is missing: enter its recovery key to put it back.')
  })
})

describe('Confirm Recovery Key', () => {
  it("takes the last group typed back, without showing the key, and refuses a wrong one in the rule's words", async () => {
    const { api, view } = await page(4, {
      'POST /v1/admin/backends/4/recovery-key/confirm': ({ body }: { body?: unknown }) =>
        (body as A['RecoveryKeyConfirmation']).lastGroup === '4JP8' ? { ...nasBackend, keyConfirmed: true, offered: true } : problem(409, 'refused', "That isn't the last group of its recovery key."),
    })
    await userEvent.click(within(main()).getByRole('button', { name: 'Confirm Recovery Key' }))
    const dialog = await screen.findByRole('dialog', { name: 'Confirm the Recovery Key of Office NAS' })
    expect(within(dialog).queryByRole('group', { name: 'Recovery Key' })).toBeNull()
    const confirm = within(dialog).getByRole('button', { name: 'Confirm' })
    expect(confirm.getAttribute('disabled')).not.toBeNull()
    await expectNoAxeViolations(view.container)
    await userEvent.type(within(dialog).getByRole('textbox', { name: 'Last Group' }), 'ABCD')
    await userEvent.click(confirm)
    expect((await within(dialog).findByRole('alert')).textContent).toBe("That isn't the last group of its recovery key.")
    await userEvent.clear(within(dialog).getByRole('textbox', { name: 'Last Group' }))
    await userEvent.type(within(dialog).getByRole('textbox', { name: 'Last Group' }), '4JP8')
    await userEvent.click(confirm)
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Confirm the Recovery Key of Office NAS' })).toBeNull())
    expect(await screen.findByText('The recovery key of Office NAS is confirmed: Stores can use it')).toBeTruthy()
    expect(api.callsTo('POST /v1/admin/backends/4/recovery-key/confirm')).toHaveLength(2)
    expect(within(main()).queryByText("Its recovery key isn't confirmed, so no Store can use it yet.")).toBeNull()
  })

  it("can't be cancelled while it's on its way", async () => {
    const slow = slowAnswer<A['Backend']>()
    const { api } = await page(4, { 'POST /v1/admin/backends/4/recovery-key/confirm': () => slow.answered })
    await userEvent.click(within(main()).getByRole('button', { name: 'Confirm Recovery Key' }))
    const dialog = await screen.findByRole('dialog', { name: 'Confirm the Recovery Key of Office NAS' })
    await userEvent.type(within(dialog).getByRole('textbox', { name: 'Last Group' }), '4JP8')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Confirm' }))
    await waitFor(() => expect(api.callsTo('POST /v1/admin/backends/4/recovery-key/confirm')).toHaveLength(1))
    expect(within(dialog).getByRole('button', { name: 'Cancel' }).getAttribute('disabled')).not.toBeNull()
    await userEvent.keyboard('{Escape}')
    expect(screen.getByRole('dialog', { name: 'Confirm the Recovery Key of Office NAS' })).toBeTruthy()
    await act(async () => slow.answer({ ...nasBackend, keyConfirmed: true, offered: true }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Confirm the Recovery Key of Office NAS' })).toBeNull())
  })
})

describe('Enter Recovery Key', () => {
  it("puts the key file back with the key typed, refusing one that isn't the backend's", async () => {
    const rendered = await page(2, {
      'POST /v1/admin/backends/2/recovery-key/enter': ({ body }: { body?: unknown }) =>
        (body as A['RecoveryKeyEntry']).recoveryKey === RECOVERY_KEY ? wasabiBackend : problem(409, 'refused', "That recovery key isn't the key of Wasabi EU, so nothing was written."),
    })
    await userEvent.click(within(main()).getByRole('button', { name: 'Enter Recovery Key' }))
    const dialog = await screen.findByRole('dialog', { name: 'Enter the Recovery Key of Wasabi EU' })
    expect(within(dialog).getByText(/writes nothing if it isn't the backend's/)).toBeTruthy()
    const field = within(dialog).getByRole('textbox', { name: 'Recovery Key' })
    expect(field.getAttribute('autocomplete')).toBe('off')
    expect(within(dialog).getByRole('button', { name: 'Enter' }).getAttribute('disabled')).not.toBeNull()
    await expectNoAxeViolations(rendered.view.container)
    await userEvent.type(field, 'ZZZZ')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Enter' }))
    expect((await within(dialog).findByRole('alert')).textContent).toBe("That recovery key isn't the key of Wasabi EU, so nothing was written.")
    await userEvent.clear(field)
    await userEvent.click(field)
    await userEvent.paste(RECOVERY_KEY)
    await userEvent.click(within(dialog).getByRole('button', { name: 'Enter' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Enter the Recovery Key of Wasabi EU' })).toBeNull())
    expect(await screen.findByText('The recovery key is right, and the key file of Wasabi EU is back')).toBeTruthy()
    expect(keptAnywhere(rendered, RECOVERY_KEY)).toBe(false)
  })
})

describe('Key History', () => {
  it('lists each showing and entering of the recovery key, newest first, with who and when', async () => {
    const { view } = await page(2)
    await userEvent.click(within(main()).getByRole('button', { name: 'Key History' }))
    const dialog = await screen.findByRole('dialog', { name: 'Key History of Wasabi EU' })
    const table = await within(dialog).findByRole('table')
    expect(within(table).getAllByRole('columnheader').map((one) => one.textContent)).toEqual(['When', 'What', 'By'])
    expect(within(table).getAllByRole('row').slice(1).map((row) => within(row).getAllByRole('cell').map((cell) => cell.textContent))).toEqual([
      [formatDateTime('2026-10-02T09:15:00Z'), 'Recovery Key Shown', 'sam'],
      [formatDateTime('2026-09-30T16:40:00Z'), 'Recovery Key Entered', 'admin'],
      [formatDateTime('2026-09-01T11:00:00Z'), 'Recovery Key Shown', 'admin'],
    ])
    await expectNoAxeViolations(view.container)
    await userEvent.click(within(dialog).getByRole('button', { name: 'Close' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Key History of Wasabi EU' })).toBeNull())
  })

  it('says when nothing is recorded', async () => {
    await page(4)
    await userEvent.click(within(main()).getByRole('button', { name: 'Key History' }))
    const dialog = await screen.findByRole('dialog', { name: 'Key History of Office NAS' })
    expect(await within(dialog).findByText('No showing or entering of its recovery key is recorded.')).toBeTruthy()
  })
})

describe('Remove', () => {
  it('asks first, naming the backend, and says why it was refused while files remain', async () => {
    const { api, view } = await page(3, { 'DELETE /v1/admin/backends/3': () => problem(409, 'refused', '3 files are recorded on this backend.') })
    await userEvent.click(within(main()).getByRole('button', { name: 'Remove' }))
    const ask = await screen.findByRole('alertdialog', { name: 'Remove Archive?' })
    expect(within(ask).getByText(/Only a backend no Store uses, with no file recorded on it and no copy held there, can be removed/)).toBeTruthy()
    expect(api.callsTo('DELETE /v1/admin/backends/3')).toEqual([])
    await expectNoAxeViolations(view.container)
    await userEvent.click(within(ask).getByRole('button', { name: 'Remove' }))
    expect((await within(ask).findByRole('alert')).textContent).toBe('3 files are recorded on this backend.')
    expect(api.callsTo('DELETE /v1/admin/backends/3')).toHaveLength(1)
  })

  it('removes an empty backend and goes back to Backends', async () => {
    const { api, router } = await page(4, { 'DELETE /v1/admin/backends/4': undefined })
    await userEvent.click(within(main()).getByRole('button', { name: 'Remove' }))
    const ask = await screen.findByRole('alertdialog', { name: 'Remove Office NAS?' })
    // An encrypted one's key file goes with it
    expect(within(ask).getByText(/Its key file is deleted too/)).toBeTruthy()
    await userEvent.click(within(ask).getByRole('button', { name: 'Remove' }))
    await waitFor(() => expect(router.state.location.pathname).toBe('/backends'))
    expect(api.callsTo('DELETE /v1/admin/backends/4')).toHaveLength(1)
    expect(await screen.findByText('Removed Office NAS')).toBeTruthy()
  })
})
