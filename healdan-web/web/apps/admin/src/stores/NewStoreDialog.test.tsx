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
import { clientWork, fakeApi, firmStore, partners, problem, regionalOffice, type Routes } from '../test/fakeApi'
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
    expect(within(dialog()).getByText("Its files are kept on Original, the server's own storage, until its storage backend is changed.")).toBeTruthy()
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

    // Nothing to make without a name
    expect(isDisabled(create())).toBe(true)
    await userEvent.type(name(), '  Regional Office ')
    await userEvent.click(create())
    await waitFor(() => expect(onClose).toHaveBeenCalledTimes(1))
    expect(sent).toEqual({ name: 'Regional Office', groupIds: [partners.id] } satisfies A['NewStore'])
    expect(onSaved).toHaveBeenCalledWith(regionalOffice)
    expect(onSaved.mock.invocationCallOrder[0]).toBeLessThan(onClose.mock.invocationCallOrder[0]!)
    // Every Store menu has it at once, by name
    expect(queryClient.getQueryData(adminKeys.stores)).toEqual([clientWork, firmStore, regionalOffice])
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
      expect(within(dialog()).getByText("That name isn't allowed.")).toBeTruthy()
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

  it('closes without making anything when it is cancelled', async () => {
    const { api, onClose, onSaved } = renderDialog()
    await userEvent.click(within(dialog()).getByRole('button', { name: 'Cancel' }))
    expect(onClose).toHaveBeenCalledTimes(1)
    expect(onSaved).not.toHaveBeenCalled()
    expect(api.callsTo('POST /v1/admin/stores')).toEqual([])
  })
})
