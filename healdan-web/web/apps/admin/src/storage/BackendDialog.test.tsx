// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { ApiError, type adminComponents } from '@healdan/api'
import { expectNoAxeViolations } from '../test/axe'
import { archiveBackend, backendOf, originalBackend, problem, RECOVERY_KEY, wasabiBackend } from '../test/fakeApi'
import { renderApp, type Rendered } from '../test/renderApp'

type A = adminComponents['schemas']

const main = () => screen.getByRole('main')
const text = (dialog: HTMLElement, name: string) => within(dialog).getByRole('textbox', { name }) as HTMLInputElement
const secretIn = (dialog: HTMLElement) => within(dialog).getByLabelText(/^Secret/) as HTMLInputElement

/** A change the server answers only when the test says, as a slow one would be */
function slowAnswer<T>() {
  let answer!: (value: T) => void
  const answered = new Promise<T>((resolve) => {
    answer = resolve
  })
  return { answered, answer }
}

/** Everything a test of the add's answers asks: what Test finds (none unless `problems` holds some), and the add's own */
function adding(over: { problems?: string[]; add?: unknown } = {}) {
  const state = { problems: over.problems ?? [] }
  const routes = {
    'POST /v1/admin/backends/test': () => ({ problems: state.problems }) satisfies A['BackendTestResult'],
    'POST /v1/admin/backends': over.add ?? (() => ({ backend: backendOf({ id: 5, name: 'Wasabi US' }), recoveryKey: null }) satisfies A['AddedBackend']),
  }
  return { state, routes }
}

/** The Backends list with Add Backend pressed */
async function openAdd(routes: Record<string, unknown> = {}): Promise<Rendered & { dialog: HTMLElement }> {
  const rendered = await renderApp('/backends', routes)
  await userEvent.click(await within(main()).findByRole('button', { name: 'Add Backend' }))
  const dialog = await screen.findByRole('dialog', { name: 'Add a Storage Backend' })
  return { ...rendered, dialog }
}

/** Wasabi US's settings typed in, an S3 bucket */
async function typeS3(dialog: HTMLElement) {
  await userEvent.type(text(dialog, 'Name'), 'Wasabi US')
  await userEvent.type(text(dialog, 'Endpoint'), 'https://s3.us-east-1.wasabisys.com')
  await userEvent.type(text(dialog, 'Region'), 'us-east-1')
  await userEvent.type(text(dialog, 'Bucket'), 'ashcombe-us')
  await userEvent.type(text(dialog, 'Prefix'), 'healdan')
  await userEvent.type(text(dialog, 'Access Key'), 'WASABIUS01')
  await userEvent.type(secretIn(dialog), 's3cret-value')
}

const S3_DRAFT = {
  kind: 's3',
  name: 'Wasabi US',
  endpoint: 'https://s3.us-east-1.wasabisys.com',
  region: 'us-east-1',
  bucket: 'ashcombe-us',
  prefix: 'healdan',
  pathStyle: false,
  accessKey: 'WASABIUS01',
  secret: 's3cret-value',
  encrypted: true,
}

/** Whether the text appears anywhere the app keeps things: the query cache, the address and the browser's storage */
function keptAnywhere(rendered: Rendered, value: string): boolean {
  const cached = JSON.stringify(rendered.queryClient.getQueryCache().getAll().map((query) => [query.queryKey, query.state.data]))
  const address = rendered.router.state.location.href
  const stored = JSON.stringify({ ...window.localStorage }) + JSON.stringify({ ...window.sessionStorage }) + JSON.stringify(window.history.state)
  return cached.includes(value) || address.includes(value) || stored.includes(value)
}

describe('Add Backend, for an S3 bucket', () => {
  it("offers S3's settings, encrypted to begin with, and tests them as typed, saving nothing", async () => {
    const { state, routes } = adding({ problems: ["The bucket ashcombe-us can't be reached at https://s3.us-east-1.wasabisys.com."] })
    const { dialog, api, view } = await openAdd(routes)
    expect((within(dialog).getByRole('radio', { name: 'S3' }) as HTMLInputElement).checked).toBe(true)
    expect((within(dialog).getByRole('checkbox', { name: 'Encrypt' }) as HTMLInputElement).checked).toBe(true)
    expect(within(dialog).queryByRole('textbox', { name: 'Path' })).toBeNull()
    expect(secretIn(dialog).getAttribute('type')).toBe('password')
    await typeS3(dialog)
    await userEvent.click(within(dialog).getByRole('button', { name: 'Test' }))
    const found = await within(dialog).findByRole('status')
    await waitFor(() => expect(found.textContent).toContain('Test found one problem:'))
    expect(within(found).getAllByRole('listitem').map((one) => one.textContent)).toEqual(state.problems)
    expect(api.callsTo('POST /v1/admin/backends/test')[0]!.body).toEqual(S3_DRAFT)
    expect(api.callsTo('POST /v1/admin/backends')).toEqual([])
    await expectNoAxeViolations(view.container)

    state.problems = []
    await userEvent.click(within(dialog).getByRole('button', { name: 'Test' }))
    await waitFor(() => expect(within(dialog).getByRole('status').textContent).toBe('Test passed.'))
  })

  it("adds it only once Test passes, as the Storage tab does, saying why it wasn't added", async () => {
    const { state, routes } = adding({ problems: ['The access key was refused.', 'The bucket is missing.'] })
    const { dialog, api } = await openAdd(routes)
    await typeS3(dialog)
    await userEvent.click(within(dialog).getByRole('button', { name: 'Add' }))
    const why = await within(dialog).findByRole('alert')
    expect(why.textContent).toContain("Test failed, so the backend wasn't added.")
    expect(within(why).getAllByRole('listitem').map((one) => one.textContent)).toEqual(state.problems)
    expect(api.callsTo('POST /v1/admin/backends')).toEqual([])

    state.problems = []
    await userEvent.click(within(dialog).getByRole('button', { name: 'Add' }))
    await waitFor(() => expect(api.callsTo('POST /v1/admin/backends')).toHaveLength(1))
    expect(api.callsTo('POST /v1/admin/backends')[0]!.body).toEqual(S3_DRAFT)
  })

  it("isn't finished until its recovery key, shown at once, is confirmed by its last group", async () => {
    const added = backendOf({ id: 5, name: 'Wasabi US', kind: 's3', path: null, encrypted: true, keyConfirmed: false, offered: false })
    let confirmed = false
    const rendered = await openAdd({
      ...adding().routes,
      'POST /v1/admin/backends': { backend: added, recoveryKey: RECOVERY_KEY } satisfies A['AddedBackend'],
      'POST /v1/admin/backends/5/recovery-key/confirm': ({ body }: { body?: unknown }) => {
        if ((body as A['RecoveryKeyConfirmation']).lastGroup !== '4JP8') return problem(409, 'refused', "That isn't the last group of the recovery key of Wasabi US.")
        confirmed = true
        return { ...added, keyConfirmed: true, offered: true }
      },
    })
    const { dialog, router, view } = rendered
    await typeS3(dialog)
    await userEvent.click(within(dialog).getByRole('button', { name: 'Add' }))

    const key = await screen.findByRole('dialog', { name: 'Recovery Key of Wasabi US' })
    expect(screen.queryByRole('dialog', { name: 'Add a Storage Backend' })).toBeNull()
    // The add's window went as this one came: the focus is on the field it asks for
    await waitFor(() => expect(document.activeElement).toBe(text(key, 'Last Group')))
    expect(within(key).getByText(/Wasabi US is encrypted\. This is its recovery key: save it now/)).toBeTruthy()
    expect(within(key).getByText(/Lose both, and its files are lost for good/)).toBeTruthy()
    expect(within(key).getByText(/isn't shown again unless an administrator asks/)).toBeTruthy()
    expect(within(key).getByRole('group', { name: 'Recovery Key' }).textContent?.replace(/\s+/g, ' ').trim()).toBe(RECOVERY_KEY)
    expect(within(key).getByText(/No Store can use Wasabi US until you do/)).toBeTruthy()
    await expectNoAxeViolations(view.container)

    // A wrong group is refused in the rule's own words, and nothing is confirmed
    await userEvent.type(text(key, 'Last Group'), '4JP9')
    await userEvent.click(within(key).getByRole('button', { name: 'Confirm' }))
    expect((await within(key).findByRole('alert')).textContent).toBe("That isn't the last group of the recovery key of Wasabi US.")
    expect(confirmed).toBe(false)

    await userEvent.clear(text(key, 'Last Group'))
    await userEvent.type(text(key, 'Last Group'), '4JP8')
    await userEvent.click(within(key).getByRole('button', { name: 'Confirm' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Recovery Key of Wasabi US' })).toBeNull())
    expect(confirmed).toBe(true)
    expect(await screen.findByText('The recovery key of Wasabi US is confirmed: Stores can use it')).toBeTruthy()
    // Then its page opens
    await waitFor(() => expect(router.state.location.pathname).toBe('/backends/5'))
    // The key was only ever in the dialog
    expect(keptAnywhere(rendered, RECOVERY_KEY)).toBe(false)
    expect(keptAnywhere(rendered, 's3cret-value')).toBe(false)
  })

  it('can be confirmed later: Later closes the key, and the backend stays unoffered', async () => {
    const added = backendOf({ id: 5, name: 'Wasabi US', kind: 's3', path: null, encrypted: true, keyConfirmed: false, offered: false })
    const rendered = await openAdd({ ...adding().routes, 'POST /v1/admin/backends': { backend: added, recoveryKey: RECOVERY_KEY } satisfies A['AddedBackend'] })
    await typeS3(rendered.dialog)
    await userEvent.click(within(rendered.dialog).getByRole('button', { name: 'Add' }))
    const key = await screen.findByRole('dialog', { name: 'Recovery Key of Wasabi US' })
    await userEvent.click(within(key).getByRole('button', { name: 'Later' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Recovery Key of Wasabi US' })).toBeNull())
    expect(rendered.api.callsTo('POST /v1/admin/backends/5/recovery-key/confirm')).toEqual([])
    await waitFor(() => expect(rendered.router.state.location.pathname).toBe('/backends/5'))
    expect(keptAnywhere(rendered, RECOVERY_KEY)).toBe(false)
  })

  it("asks before making a new secret key when Healdan's is missing, and sends the add again with newKey", async () => {
    const missing = new ApiError(409, 'secret-key-missing', "Healdan's secret key file is missing, and 3 stored values are sealed with it.", {
      code: 'secret-key-missing',
      detail: "Healdan's secret key file is missing, and 3 stored values are sealed with it.",
      sealed: 3,
    })
    const { dialog, api, router } = await openAdd({
      ...adding().routes,
      'POST /v1/admin/backends': ({ body }: { body?: unknown }) =>
        (body as A['NewBackend']).newKey ? ({ backend: backendOf({ id: 5, name: 'Wasabi US', kind: 's3', path: null }), recoveryKey: null } satisfies A['AddedBackend']) : missing,
    })
    await typeS3(dialog)
    await userEvent.click(within(dialog).getByRole('button', { name: 'Add' }))
    const question = await within(dialog).findByRole('alert')
    expect(question.textContent).toContain("Healdan's secret key file is missing, and 3 stored values are sealed with it.")
    expect(question.textContent).toContain('restore it from a backup')
    expect(question.textContent).toContain("the 3 values sealed with the missing one can't be opened again")
    expect(api.callsTo('POST /v1/admin/backends')).toHaveLength(1)

    await userEvent.click(within(dialog).getByRole('button', { name: 'Make a New Key' }))
    await waitFor(() => expect(api.callsTo('POST /v1/admin/backends')).toHaveLength(2))
    expect(api.callsTo('POST /v1/admin/backends')[1]!.body).toEqual({ ...S3_DRAFT, newKey: true })
    await waitFor(() => expect(router.state.location.pathname).toBe('/backends/5'))
    expect(await screen.findByText('Added Wasabi US')).toBeTruthy()
  })

  it("can't be cancelled while the add is on its way, by Cancel or by Esc", async () => {
    const slow = slowAnswer<A['AddedBackend']>()
    const { dialog, api, router } = await openAdd({ ...adding().routes, 'POST /v1/admin/backends': () => slow.answered })
    await typeS3(dialog)
    await userEvent.click(within(dialog).getByRole('button', { name: 'Add' }))
    await waitFor(() => expect(api.callsTo('POST /v1/admin/backends')).toHaveLength(1))
    const cancel = within(dialog).getByRole('button', { name: 'Cancel' })
    expect(cancel.getAttribute('disabled')).not.toBeNull()
    await userEvent.click(cancel)
    await userEvent.keyboard('{Escape}')
    expect(screen.getByRole('dialog', { name: 'Add a Storage Backend' })).toBeTruthy()
    await act(async () => slow.answer({ backend: backendOf({ id: 5, name: 'Wasabi US' }), recoveryKey: null }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Add a Storage Backend' })).toBeNull())
    await waitFor(() => expect(router.state.location.pathname).toBe('/backends/5'))
  })

  it("says a refusal in the rule's words, and a field the server names beside it", async () => {
    const invalid = new ApiError(422, 'validation', 'Some details need fixing.', { code: 'validation', fields: [{ field: 'region', code: 'required', detail: 'Region is required.' }] })
    let answer: unknown = problem(409, 'refused', 'Another backend has that bucket, at the same S3 service, with the same prefix.')
    const { dialog } = await openAdd({ ...adding().routes, 'POST /v1/admin/backends': () => answer })
    await typeS3(dialog)
    await userEvent.click(within(dialog).getByRole('button', { name: 'Add' }))
    expect((await within(dialog).findByRole('alert')).textContent).toBe('Another backend has that bucket, at the same S3 service, with the same prefix.')
    answer = invalid
    await userEvent.click(within(dialog).getByRole('button', { name: 'Add' }))
    await waitFor(() => expect(text(dialog, 'Region').getAttribute('aria-invalid')).toBe('true'))
    expect(within(dialog).getByText('This needs a value.')).toBeTruthy()
  })
})

describe('Add Backend, for a local folder', () => {
  it('asks for its path, unencrypted to begin with, and opens its page once added', async () => {
    const added = backendOf({ id: 6, name: 'Second Archive', path: '/mnt/archive2' })
    const { dialog, api, router } = await openAdd({ ...adding().routes, 'POST /v1/admin/backends': { backend: added, recoveryKey: null } satisfies A['AddedBackend'] })
    await userEvent.click(within(dialog).getByRole('radio', { name: 'Local Folder' }))
    expect((within(dialog).getByRole('checkbox', { name: 'Encrypt' }) as HTMLInputElement).checked).toBe(false)
    expect(within(dialog).queryByRole('textbox', { name: 'Endpoint' })).toBeNull()
    expect(within(dialog).queryByLabelText(/^Secret/)).toBeNull()
    const add = within(dialog).getByRole('button', { name: 'Add' })
    // What the kind needs is said before the button
    expect(add.getAttribute('disabled')).not.toBeNull()
    await userEvent.type(text(dialog, 'Name'), 'Second Archive')
    expect(add.getAttribute('disabled')).not.toBeNull()
    await userEvent.type(text(dialog, 'Path'), '/mnt/archive2')
    expect(add.getAttribute('disabled')).toBeNull()
    await userEvent.click(add)
    await waitFor(() => expect(api.callsTo('POST /v1/admin/backends')).toHaveLength(1))
    expect(api.callsTo('POST /v1/admin/backends/test')[0]!.body).toEqual({ kind: 'local', name: 'Second Archive', path: '/mnt/archive2', encrypted: false })
    expect(api.callsTo('POST /v1/admin/backends')[0]!.body).toEqual({ kind: 'local', name: 'Second Archive', path: '/mnt/archive2', encrypted: false })
    expect(await screen.findByText('Added Second Archive')).toBeTruthy()
    await waitFor(() => expect(router.state.location.pathname).toBe('/backends/6'))
    expect(screen.queryByRole('dialog', { name: /Recovery Key/ })).toBeNull()
  })

  it('can be encrypted, which its add chooses for good', async () => {
    const { dialog, api } = await openAdd(adding().routes)
    await userEvent.click(within(dialog).getByRole('radio', { name: 'Local Folder' }))
    await userEvent.click(within(dialog).getByRole('checkbox', { name: 'Encrypt' }))
    expect(within(dialog).getByText(/can't change once the backend is added/)).toBeTruthy()
    await userEvent.type(text(dialog, 'Name'), 'NAS')
    await userEvent.type(text(dialog, 'Path'), '/mnt/nas2')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Test' }))
    await waitFor(() => expect(api.callsTo('POST /v1/admin/backends/test')).toHaveLength(1))
    expect(api.callsTo('POST /v1/admin/backends/test')[0]!.body).toEqual({ kind: 'local', name: 'NAS', path: '/mnt/nas2', encrypted: true })
  })

  it('says a name too long before the button', async () => {
    const { dialog } = await openAdd(adding().routes)
    await userEvent.click(within(dialog).getByRole('radio', { name: 'Local Folder' }))
    await userEvent.type(text(dialog, 'Path'), '/mnt/x')
    await userEvent.click(text(dialog, 'Name'))
    await userEvent.paste('n'.repeat(101))
    expect(within(dialog).getByText('That is too long.')).toBeTruthy()
    expect(within(dialog).getByRole('button', { name: 'Add' }).getAttribute('disabled')).not.toBeNull()
  })
})

describe("Editing a backend, from its page", () => {
  async function openEdit(id: number, routes: Record<string, unknown> = {}) {
    const rendered = await renderApp(`/backends/${id}`, { 'POST /v1/admin/backends/test': { problems: [] } satisfies A['BackendTestResult'], ...routes })
    await userEvent.click(await within(main()).findByRole('button', { name: 'Edit' }))
    const name = (rendered.queryClient.getQueryData(['admin', 'backends', id]) as A['Backend'] | undefined)?.name
    const dialog = await screen.findByRole('dialog', { name: `Edit ${name}` })
    return { ...rendered, dialog }
  }

  it("keeps S3's bucket and prefix once files are there, its kind and encryption for good, and its secret unless one is typed", async () => {
    const saved = { ...wasabiBackend, region: 'eu-central-2' }
    const { dialog, api, view } = await openEdit(2, { 'PATCH /v1/admin/backends/2': saved })
    expect(within(dialog).queryByRole('radio')).toBeNull()
    expect(within(dialog).queryByRole('checkbox', { name: 'Encrypt' })).toBeNull()
    expect(within(dialog).getByText('Encrypted, chosen when the backend was added.')).toBeTruthy()
    expect(within(dialog).queryByRole('textbox', { name: 'Bucket' })).toBeNull()
    expect(within(dialog).getByText('ashcombe-reeve')).toBeTruthy()
    expect(within(dialog).getAllByText("Can't change once files are there.")).toHaveLength(2)
    expect(secretIn(dialog).value).toBe('')
    expect(within(dialog).getByText('Set: leave empty to keep it.')).toBeTruthy()
    expect(text(dialog, 'Access Key').value).toBe('WASABIACCESSKEY01')
    // Nothing to save until something changes
    expect(within(dialog).getByRole('button', { name: 'Save' }).getAttribute('disabled')).not.toBeNull()
    await expectNoAxeViolations(view.container)

    await userEvent.clear(text(dialog, 'Region'))
    await userEvent.type(text(dialog, 'Region'), 'eu-central-2')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(api.callsTo('PATCH /v1/admin/backends/2')).toHaveLength(1))
    // Tested first, as the backend being edited, its stored secret standing in for an empty one
    expect(api.callsTo('POST /v1/admin/backends/test')[0]!.body).toEqual({
      id: 2,
      kind: 's3',
      name: 'Wasabi EU',
      endpoint: 'https://s3.eu-central-1.wasabisys.com',
      region: 'eu-central-2',
      bucket: 'ashcombe-reeve',
      prefix: 'healdan/',
      pathStyle: true,
      accessKey: 'WASABIACCESSKEY01',
      secret: null,
      encrypted: true,
    })
    // Only what changed, so nothing the kind doesn't have, and no secret
    expect(api.callsTo('PATCH /v1/admin/backends/2')[0]!.body).toEqual({ region: 'eu-central-2' })
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Edit Wasabi EU' })).toBeNull())
    expect(await screen.findByText('Saved Wasabi EU')).toBeTruthy()
  })

  it('asks whether to save settings Test finds problems with, as the backend fails until they work', async () => {
    const { dialog, api } = await openEdit(2, {
      'POST /v1/admin/backends/test': { problems: ["This backend can't be reached."] } satisfies A['BackendTestResult'],
      'PATCH /v1/admin/backends/2': { ...wasabiBackend, name: 'Wasabi Frankfurt' },
    })
    await userEvent.clear(text(dialog, 'Name'))
    await userEvent.type(text(dialog, 'Name'), 'Wasabi Frankfurt')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Save' }))
    const ask = await within(dialog).findByRole('alert')
    expect(ask.textContent).toContain('Test found one problem:')
    expect(ask.textContent).toContain("This backend can't be reached.")
    expect(ask.textContent).toContain('Save these settings anyway? The backend fails until they work.')
    expect(api.callsTo('PATCH /v1/admin/backends/2')).toEqual([])
    await userEvent.click(within(dialog).getByRole('button', { name: 'Save Anyway' }))
    await waitFor(() => expect(api.callsTo('PATCH /v1/admin/backends/2')).toHaveLength(1))
    expect(api.callsTo('PATCH /v1/admin/backends/2')[0]!.body).toEqual({ name: 'Wasabi Frankfurt' })
  })

  it('replaces the secret only with one typed, and asks before a new secret key', async () => {
    const missing = new ApiError(409, 'secret-key-missing', "Healdan's secret key file is missing, and 1 stored value is sealed with it.", { code: 'secret-key-missing', sealed: 1 })
    const { dialog, api } = await openEdit(2, {
      'GET /v1/admin/backends/2': { ...wasabiBackend, hasSecret: false },
      'PATCH /v1/admin/backends/2': ({ body }: { body?: unknown }) => ((body as A['BackendChange']).newKey ? wasabiBackend : missing),
    })
    expect(within(dialog).getByText('Not Set: enter it again.')).toBeTruthy()
    await userEvent.type(secretIn(dialog), 'new-secret')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Save' }))
    expect((await within(dialog).findByRole('alert')).textContent).toContain("the 1 value sealed with the missing one can't be opened again")
    await userEvent.click(within(dialog).getByRole('button', { name: 'Make a New Key' }))
    await waitFor(() => expect(api.callsTo('PATCH /v1/admin/backends/2')).toHaveLength(2))
    expect(api.callsTo('PATCH /v1/admin/backends/2').map((call) => call.body)).toEqual([{ secret: 'new-secret' }, { secret: 'new-secret', newKey: true }])
    expect(api.callsTo('POST /v1/admin/backends/test')[0]!.body).toMatchObject({ id: 2, secret: 'new-secret' })
  })

  it("renames an S3 backend whose endpoint can't be read, leaving the endpoint out until one is typed", async () => {
    const { dialog, api } = await openEdit(2, { 'GET /v1/admin/backends/2': { ...wasabiBackend, endpoint: null }, 'PATCH /v1/admin/backends/2': wasabiBackend })
    expect(text(dialog, 'Endpoint').value).toBe('')
    await userEvent.clear(text(dialog, 'Name'))
    await userEvent.type(text(dialog, 'Name'), 'Wasabi Frankfurt')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(api.callsTo('PATCH /v1/admin/backends/2')).toHaveLength(1))
    expect(api.callsTo('PATCH /v1/admin/backends/2')[0]!.body).toEqual({ name: 'Wasabi Frankfurt' })
  })

  it("won't save a setting the kind needs emptied, which the server keeps", async () => {
    const { dialog } = await openEdit(2)
    await userEvent.clear(text(dialog, 'Region'))
    expect(within(dialog).getByRole('button', { name: 'Save' }).getAttribute('disabled')).not.toBeNull()
  })

  it('renames Original, and nothing else about it', async () => {
    const { dialog, api } = await openEdit(1, { 'PATCH /v1/admin/backends/1': { ...originalBackend, name: 'Server Disk' } })
    expect(within(dialog).getAllByRole('textbox')).toEqual([text(dialog, 'Name')])
    expect(within(dialog).queryByLabelText(/^Secret/)).toBeNull()
    expect(within(dialog).getByText('/opt/logicaldoc/repository/docs')).toBeTruthy()
    expect(within(dialog).getByText("From the server's configuration.")).toBeTruthy()
    expect(within(dialog).getByText('Original can be renamed, but nothing else about it changes.')).toBeTruthy()
    await userEvent.clear(text(dialog, 'Name'))
    await userEvent.type(text(dialog, 'Name'), 'Server Disk')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(api.callsTo('PATCH /v1/admin/backends/1')).toHaveLength(1))
    expect(api.callsTo('PATCH /v1/admin/backends/1')[0]!.body).toEqual({ name: 'Server Disk' })
  })

  it("moves an empty folder no Store uses, which can still change its path", async () => {
    const { dialog, api } = await openEdit(3, { 'PATCH /v1/admin/backends/3': { ...archiveBackend, path: '/mnt/archive-new' } })
    expect(text(dialog, 'Path').value).toBe('/mnt/archive')
    await userEvent.clear(text(dialog, 'Path'))
    await userEvent.type(text(dialog, 'Path'), '/mnt/archive-new')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(api.callsTo('PATCH /v1/admin/backends/3')).toHaveLength(1))
    expect(api.callsTo('PATCH /v1/admin/backends/3')[0]!.body).toEqual({ path: '/mnt/archive-new' })
  })

  it('keeps the path of a folder a Store keeps its files on', async () => {
    const { dialog } = await openEdit(3, { 'GET /v1/admin/backends/3': { ...archiveBackend, stores: ['Firm'] } })
    expect(within(dialog).queryByRole('textbox', { name: 'Path' })).toBeNull()
    expect(within(dialog).getByText("Can't change while a Store keeps its files here.")).toBeTruthy()
  })
})
