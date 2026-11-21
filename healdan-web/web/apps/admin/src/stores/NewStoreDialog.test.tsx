// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { ApiError, type adminComponents } from '@healdan/api'
import { createQueryClient, createSignedOut, MessagesProvider, ServicesProvider } from '@healdan/shell'
import { ToastRegion } from '@healdan/ui'
import { TYPING_DELAY_MS } from '../pickers/typing'
import { adminKeys } from '../queries'
import { problemLookup } from '../strings'
import { expectNoAxeViolations } from '../test/axe'
import { clientWork, fakeApi, firmStore, partners, problem, regionalOffice, storageOff, wasabiBackend, type Routes } from '../test/fakeApi'
import { NewStoreDialog } from './NewStoreDialog'

type A = adminComponents['schemas']
type Sent = { body?: unknown }

/** Long enough for the typing to pause and the answer to come: a search waits as long as the app's pickers do */
const pause = () => act(() => new Promise((resolve) => setTimeout(resolve, TYPING_DELAY_MS + 100)))

/** The dialog on its own, as New Matter's New Store… opens it, over the fake API */
function renderDialog(routes: Routes = {}) {
  const api = fakeApi(routes)
  const queryClient = createQueryClient({ retry: false })
  queryClient.setQueryData(adminKeys.stores, [clientWork, firmStore])
  const onClose = vi.fn()
  const onSaved = vi.fn<(store: A['Store']) => void>()
  const view = render(
    <ServicesProvider api={api} signedOut={createSignedOut()}>
      <QueryClientProvider client={queryClient}>
        <MessagesProvider lookup={problemLookup}>
          <NewStoreDialog onClose={onClose} onSaved={onSaved} />
          <ToastRegion />
        </MessagesProvider>
      </QueryClientProvider>
    </ServicesProvider>,
  )
  return { api, queryClient, onClose, onSaved, view }
}

const dialog = () => screen.getByRole('dialog', { name: 'New Store' })
const name = () => within(dialog()).getByRole('textbox', { name: 'Name' }) as HTMLInputElement
const create = () => within(dialog()).getByRole('button', { name: 'Create Store' })
const isDisabled = (button: HTMLElement) => button.getAttribute('disabled') !== null
/** Who can see it so far, by name: no list while nobody can but the administrators */
const seeing = () => {
  const list = within(dialog()).queryByRole('list', { name: 'Who Can See It' })
  return list ? within(list).getAllByRole('listitem').map((item) => item.firstChild?.textContent) : []
}
const offered = () => within(within(dialog()).getByRole('listbox')).getAllByRole('option').map((option) => option.textContent)
const backend = () => within(dialog()).getByRole('combobox', { name: 'Backend' }) as HTMLSelectElement
const backendChoices = () => Array.from(backend().options, (option) => option.textContent)

describe('New Store', () => {
  it('asks for the name and who can see it, from the groups, and makes the Store', async () => {
    let sent: unknown
    const { api, onSaved, onClose, queryClient, view } = renderDialog({
      'POST /v1/admin/stores': ({ body }: Sent) => {
        sent = body
        return regionalOffice
      },
    })
    expect(document.activeElement).toBe(name())
    // Nobody yet, but the administrators
    expect(within(dialog()).getByText('Only administrators can see it until a group is added.')).toBeTruthy()
    expect(seeing()).toEqual([])
    // The backends a Store can be given, Original first and chosen
    await waitFor(() => expect(backendChoices()).toEqual(['Original', 'Wasabi EU (Encrypted)', 'Archive']))
    expect(backend().selectedOptions[0]?.textContent).toBe('Original')
    // The groups to add, each by name, with its description
    await waitFor(() => expect(offered()).toEqual(['Everyone Everyone in the firm', 'Partners', 'Trainees Trainee solicitors']))
    // Under the dialog's own title
    expect(within(dialog()).getByRole('heading', { level: 3, name: 'Groups' })).toBeTruthy()
    expect(api.callsTo('GET /v1/admin/groups').map((call) => call.query)).toEqual([{ limit: 50 }])
    await expectNoAxeViolations(view.baseElement)

    await userEvent.click(within(dialog()).getByRole('option', { name: /^Everyone/ }))
    await userEvent.click(within(dialog()).getByRole('option', { name: 'Partners' }))
    expect(seeing()).toEqual(['Everyone', 'Partners'])
    // A group added isn't offered again
    expect(offered()).toEqual(['Trainees Trainee solicitors'])
    expect(within(dialog()).queryByText('Only administrators can see it until a group is added.')).toBeNull()
    await expectNoAxeViolations(view.baseElement)
    // And one can be taken away again, which offers it again
    await userEvent.click(within(dialog()).getByRole('button', { name: 'Remove Everyone' }))
    expect(seeing()).toEqual(['Partners'])
    // The focus goes to the next group's Remove, as the one pressed has gone
    expect(document.activeElement).toBe(within(dialog()).getByRole('button', { name: 'Remove Partners' }))
    expect(offered()).toEqual(['Everyone Everyone in the firm', 'Trainees Trainee solicitors'])
    // With no group after it, to Add a Group
    await userEvent.click(within(dialog()).getByRole('option', { name: 'Trainees Trainee solicitors' }))
    expect(seeing()).toEqual(['Partners', 'Trainees'])
    await userEvent.click(within(dialog()).getByRole('button', { name: 'Remove Trainees' }))
    expect(document.activeElement).toBe(within(dialog()).getByRole('textbox', { name: 'Add a Group' }))
    await userEvent.click(within(dialog()).getByRole('button', { name: 'Remove Partners' }))
    expect(seeing()).toEqual([])
    expect(document.activeElement).toBe(within(dialog()).getByRole('textbox', { name: 'Add a Group' }))
    await userEvent.click(within(dialog()).getByRole('option', { name: 'Partners' }))

    // Nothing to make without a name
    expect(isDisabled(create())).toBe(true)
    await userEvent.type(name(), '  Regional Office ')
    await userEvent.click(create())
    await waitFor(() => expect(onClose).toHaveBeenCalledTimes(1))
    // On Original, which needs no backend named
    expect(sent).toEqual({ name: 'Regional Office', groupIds: [partners.id] } satisfies A['NewStore'])
    expect(onSaved).toHaveBeenCalledWith(regionalOffice)
    expect(onSaved.mock.invocationCallOrder[0]).toBeLessThan(onClose.mock.invocationCallOrder[0]!)
    // The Stores are asked for again, as the list's rows carry more than the create's answer says: none is made up here
    expect(queryClient.getQueryState(adminKeys.stores)?.isInvalidated).toBe(true)
    expect(queryClient.getQueryData(adminKeys.stores)).toEqual([clientWork, firmStore])
    expect(await screen.findByText('Created Regional Office')).toBeTruthy()
  })

  it('makes a Store only administrators can see, when no group is added', async () => {
    let sent: unknown
    const { onClose } = renderDialog({
      'POST /v1/admin/stores': ({ body }: Sent) => {
        sent = body
        return regionalOffice
      },
    })
    await userEvent.type(name(), 'Regional Office')
    await userEvent.keyboard('{Enter}')
    await waitFor(() => expect(onClose).toHaveBeenCalledTimes(1))
    expect(sent).toEqual({ name: 'Regional Office', groupIds: [] })
  })

  it('keeps the new Store on the backend chosen, of those a Store can be given', async () => {
    let sent: unknown
    const { view } = renderDialog({
      'POST /v1/admin/stores': ({ body }: Sent) => {
        sent = body
        return { ...regionalOffice, backendId: wasabiBackend.id } satisfies A['Store']
      },
    })
    await waitFor(() => expect(backendChoices()).toEqual(['Original', 'Wasabi EU (Encrypted)', 'Archive']))
    // Office NAS isn't offered: its recovery key isn't confirmed
    expect(within(dialog()).getByText("A backend is offered once it can be used: an encrypted one once its recovery key is confirmed. It can be changed later, on the Store's page.")).toBeTruthy()
    await userEvent.selectOptions(backend(), 'Wasabi EU (Encrypted)')
    await userEvent.type(name(), 'Regional Office')
    await expectNoAxeViolations(view.baseElement)
    await userEvent.click(create())
    await waitFor(() => expect(sent).toEqual({ name: 'Regional Office', groupIds: [], backendId: wasabiBackend.id } satisfies A['NewStore']))
    expect(await screen.findByText('Created Regional Office')).toBeTruthy()
  })

  it("says the Store was made on Original when it couldn't be given the backend chosen", async () => {
    // The answer names the backend it ended on: Original, as the one chosen was removed as the Store was made
    const { onSaved } = renderDialog({ 'POST /v1/admin/stores': () => regionalOffice })
    await waitFor(() => expect(backendChoices()).toEqual(['Original', 'Wasabi EU (Encrypted)', 'Archive']))
    await userEvent.selectOptions(backend(), 'Wasabi EU (Encrypted)')
    await userEvent.type(name(), 'Regional Office')
    await userEvent.click(create())
    expect(await screen.findByText("Created Regional Office on Original, as it couldn't be given Wasabi EU. Its backend can be changed on the Store's page.")).toBeTruthy()
    expect(onSaved).toHaveBeenCalledWith(regionalOffice)
  })

  it("asks for no backend on a server that doesn't use storage backends, or doesn't have Healdan's storage module", async () => {
    for (const answer of [storageOff, problem(503, 'storage-unavailable')]) {
      let sent: unknown
      const { api, view } = renderDialog({
        'GET /v1/admin/backends': () => answer,
        'POST /v1/admin/stores': ({ body }: Sent) => {
          sent = body
          return regionalOffice
        },
      })
      await waitFor(() => expect(api.callsTo('GET /v1/admin/backends')).toHaveLength(1))
      await userEvent.type(name(), 'Regional Office')
      expect(within(dialog()).queryByRole('combobox', { name: 'Backend' })).toBeNull()
      expect(within(dialog()).queryByText(/A backend is offered/)).toBeNull()
      expect(within(dialog()).queryByText(/The backends couldn't be read/)).toBeNull()
      await userEvent.click(create())
      await waitFor(() => expect(sent).toEqual({ name: 'Regional Office', groupIds: [] }))
      view.unmount()
    }
  })

  it("says the Store will be made on Original when the backends couldn't be read, and can be moved after", async () => {
    let sent: unknown
    const { api, view } = renderDialog({
      'GET /v1/admin/backends': () => problem(500, 'server-error'),
      'POST /v1/admin/stores': ({ body }: Sent) => {
        sent = body
        return regionalOffice
      },
    })
    await waitFor(() => expect(api.callsTo('GET /v1/admin/backends')).toHaveLength(1))
    expect(await within(dialog()).findByText("The backends couldn't be read, so the Store will be made on Original. Its backend can be changed later, on the Store's page.")).toBeTruthy()
    expect(within(dialog()).queryByRole('combobox', { name: 'Backend' })).toBeNull()
    await expectNoAxeViolations(view.baseElement)
    await userEvent.type(name(), 'Regional Office')
    await userEvent.click(create())
    await waitFor(() => expect(sent).toEqual({ name: 'Regional Office', groupIds: [] }))
  })

  it('finds a group by its name, once the typing pauses', async () => {
    const { api } = renderDialog()
    await waitFor(() => expect(offered()).toHaveLength(3))
    await userEvent.type(within(dialog()).getByRole('textbox', { name: 'Add a Group' }), 'train')
    await pause()
    await waitFor(() => expect(offered()).toEqual(['Trainees Trainee solicitors']))
    expect(api.callsTo('GET /v1/admin/groups').at(-1)?.query).toEqual({ q: 'train', limit: 20 })
    await userEvent.keyboard('{ArrowDown}{Enter}')
    expect(seeing()).toEqual(['Trainees'])
  })

  it("says what's wrong with the name before Create Store", async () => {
    renderDialog()
    for (const typed of ['North/South', 'North\\South']) {
      fireEvent.change(name(), { target: { value: typed } })
      // In the words every folder name's check uses
      expect(within(dialog()).getByText("A name can't have / or \\ in it.")).toBeTruthy()
      expect(isDisabled(create())).toBe(true)
    }
    fireEvent.change(name(), { target: { value: 'x'.repeat(256) } })
    expect(within(dialog()).getByText('That is too long.')).toBeTruthy()
    expect(isDisabled(create())).toBe(true)
    fireEvent.change(name(), { target: { value: 'x'.repeat(255) } })
    expect(within(dialog()).queryByText('That is too long.')).toBeNull()
    expect(isDisabled(create())).toBe(false)
    fireEvent.change(name(), { target: { value: '   ' } })
    expect(isDisabled(create())).toBe(true)
  })

  it("shows the server's refusal in the rule's words, and a field it says is wrong beside the field, and the dialog stays", async () => {
    let answer: ApiError = problem(409, 'refused', 'A Store named Regional Office already exists.')
    const { onClose, view } = renderDialog({ 'POST /v1/admin/stores': () => answer })
    await userEvent.type(name(), 'Regional Office')
    await userEvent.click(create())
    expect((await within(dialog()).findByRole('alert')).textContent).toBe('A Store named Regional Office already exists.')
    await expectNoAxeViolations(view.baseElement)

    answer = new ApiError(422, 'validation', 'Server words.', { code: 'validation', fields: [{ field: 'name', code: 'invalid', detail: 'Server words.' }] })
    await userEvent.type(name(), ' Two')
    // A change takes the refusal away
    expect(within(dialog()).queryByRole('alert')).toBeNull()
    await userEvent.click(create())
    await waitFor(() => expect(name().getAttribute('aria-invalid')).toBe('true'))
    expect(within(dialog()).getByText("That name isn't allowed.")).toBeTruthy()
    expect(within(dialog()).queryByText('Server words.')).toBeNull()
    expect(onClose).not.toHaveBeenCalled()
  })

  it("can't be cancelled, by Cancel or by Esc, while Create Store is on its way, so nothing is made that the person thinks they cancelled", async () => {
    let answer!: (store: A['Store']) => void
    const answered = new Promise<A['Store']>((resolve) => {
      answer = resolve
    })
    const { api, onClose, onSaved } = renderDialog({ 'POST /v1/admin/stores': () => answered })
    await userEvent.type(name(), 'Regional Office')
    await userEvent.click(create())
    await waitFor(() => expect(api.callsTo('POST /v1/admin/stores')).toHaveLength(1))
    const cancel = within(dialog()).getByRole('button', { name: 'Cancel' })
    expect(isDisabled(cancel)).toBe(true)
    await userEvent.click(cancel)
    await userEvent.keyboard('{Escape}')
    expect(onClose).not.toHaveBeenCalled()
    expect(dialog()).toBeTruthy()

    // The Store is made: the dialog hands it on and closes, as it does when nothing held it up
    await act(async () => answer(regionalOffice))
    expect(onSaved).toHaveBeenCalledWith(regionalOffice)
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('closes without making anything when it is cancelled', async () => {
    const { api, onClose, onSaved } = renderDialog()
    await userEvent.click(within(dialog()).getByRole('button', { name: 'Cancel' }))
    expect(onClose).toHaveBeenCalledTimes(1)
    expect(onSaved).not.toHaveBeenCalled()
    expect(api.callsTo('POST /v1/admin/stores')).toEqual([])
  })
})
