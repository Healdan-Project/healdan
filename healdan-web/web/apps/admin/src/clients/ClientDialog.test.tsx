// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { adminComponents } from '@healdan/api'
import { createQueryClient, createSignedOut, MessagesProvider, ServicesProvider } from '@healdan/shell'
import { adminKeys } from '../queries'
import { problemLookup } from '../strings'
import { clientOf, fakeApi, problem, saltmarsh, type Routes } from '../test/fakeApi'
import { ClientDialog, type ClientDialogProps } from './ClientDialog'

type A = adminComponents['schemas']

/** The dialog on its own, as another form opens it (New Matter's New Client…), over the fake API */
function renderDialog(props: Partial<ClientDialogProps>, routes: Routes = {}) {
  const api = fakeApi(routes)
  const queryClient = createQueryClient({ retry: false })
  const onClose = vi.fn()
  const onSaved = vi.fn<(client: A['Client']) => void>()
  render(
    <ServicesProvider api={api} signedOut={createSignedOut()}>
      <QueryClientProvider client={queryClient}>
        <MessagesProvider lookup={problemLookup}>
          <ClientDialog onClose={onClose} onSaved={onSaved} {...props} />
        </MessagesProvider>
      </QueryClientProvider>
    </ServicesProvider>,
  )
  return { api, queryClient, onClose, onSaved }
}

describe('The client dialog, as another form opens it', () => {
  it('hands on the new client, then closes, and leaves where to go to its opener', async () => {
    const made = clientOf({ id: 12, number: 1005, name: 'Marsh Lane Hall Trust' }, { matters: 0 })
    const { onSaved, onClose, queryClient } = renderDialog({ initialName: 'Marsh Lane Hall Trust' }, { 'POST /v1/admin/clients': made })
    const dialog = await screen.findByRole('dialog', { name: 'New Client' })
    // It starts from the name its opener had, as what was typed in a client search
    expect(within(dialog).getByRole('textbox', { name: 'Name' })).toHaveProperty('value', 'Marsh Lane Hall Trust')
    await waitFor(() => expect(within(dialog).getByRole('textbox', { name: 'Number' })).toHaveProperty('value', '1005'))
    await userEvent.click(within(dialog).getByRole('button', { name: 'Create Client' }))
    await waitFor(() => expect(onClose).toHaveBeenCalledTimes(1))
    expect(onSaved).toHaveBeenCalledWith(made)
    expect(onSaved.mock.invocationCallOrder[0]).toBeLessThan(onClose.mock.invocationCallOrder[0]!)
    // Its page can draw it at once
    expect(queryClient.getQueryData(adminKeys.client(12))).toEqual(made)
  })

  it('hands on the renamed client', async () => {
    const renamed = clientOf({ ...saltmarsh, name: 'Saltmarsh Shipping Group' })
    const { onSaved, onClose } = renderDialog({ client: saltmarsh }, { 'PATCH /v1/admin/clients/1': renamed })
    const dialog = await screen.findByRole('dialog', { name: 'Rename Client' })
    const name = within(dialog).getByRole('textbox', { name: 'Name' })
    await userEvent.clear(name)
    await userEvent.type(name, 'Saltmarsh Shipping Group')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Rename' }))
    await waitFor(() => expect(onClose).toHaveBeenCalledTimes(1))
    expect(onSaved).toHaveBeenCalledWith(renamed)
  })

  it('closes without handing anything on when it is cancelled', async () => {
    const { onSaved, onClose, api } = renderDialog({})
    const dialog = await screen.findByRole('dialog', { name: 'New Client' })
    await userEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }))
    expect(onClose).toHaveBeenCalledTimes(1)
    expect(onSaved).not.toHaveBeenCalled()
    expect(api.callsTo('POST /v1/admin/clients')).toEqual([])
  })
})

describe('The client dialog while its change is on its way', () => {
  /** A change the server answers only when the test says, as a slow one would be */
  function slowAnswer() {
    let answer!: (client: A['Client']) => void
    let refuse!: (error: unknown) => void
    const answered = new Promise<A['Client']>((resolve, reject) => {
      answer = resolve
      refuse = reject
    })
    return { answered, answer, refuse }
  }

  /** New Client with a name typed and Create Client pressed, its answer still to come */
  async function creating(answered: Promise<A['Client']>) {
    const rendered = renderDialog({ initialName: 'Fenwick' }, { 'POST /v1/admin/clients': () => answered })
    const dialog = await screen.findByRole('dialog', { name: 'New Client' })
    await waitFor(() => expect(within(dialog).getByRole('textbox', { name: 'Number' })).toHaveProperty('value', '1005'))
    await userEvent.click(within(dialog).getByRole('button', { name: 'Create Client' }))
    await waitFor(() => expect(rendered.api.callsTo('POST /v1/admin/clients')).toHaveLength(1))
    return { ...rendered, dialog }
  }

  it("can't be cancelled, by Cancel or by Esc, so nothing is made that the person thinks they cancelled", async () => {
    const slow = slowAnswer()
    const { dialog, onClose, onSaved } = await creating(slow.answered)
    const cancel = within(dialog).getByRole('button', { name: 'Cancel' })
    expect(cancel.getAttribute('disabled')).not.toBeNull()
    await userEvent.click(cancel)
    await userEvent.keyboard('{Escape}')
    expect(onClose).not.toHaveBeenCalled()
    expect(screen.getByRole('dialog', { name: 'New Client' })).toBeTruthy()

    // The client is made: the dialog hands it on and closes, as it does when nothing held it up
    const made = clientOf({ id: 12, number: 1005, name: 'Fenwick' })
    await act(async () => slow.answer(made))
    expect(onSaved).toHaveBeenCalledWith(made)
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('can be cancelled again once the server refuses, and then hands nothing on', async () => {
    const slow = slowAnswer()
    const { dialog, onClose, onSaved } = await creating(slow.answered)
    await act(async () => slow.refuse(problem(409, 'refused', 'Client number 1005 is already used.')))
    expect((await within(dialog).findByRole('alert')).textContent).toBe('Client number 1005 is already used.')
    const cancel = within(dialog).getByRole('button', { name: 'Cancel' })
    expect(cancel.getAttribute('disabled')).toBeNull()
    await userEvent.keyboard('{Escape}')
    expect(onClose).toHaveBeenCalledTimes(1)
    expect(onSaved).not.toHaveBeenCalled()
  })

  it("can't be cancelled while a rename is on its way either", async () => {
    const slow = slowAnswer()
    const { onClose, onSaved, api } = renderDialog({ client: saltmarsh }, { 'PATCH /v1/admin/clients/1': () => slow.answered })
    const dialog = await screen.findByRole('dialog', { name: 'Rename Client' })
    await userEvent.type(within(dialog).getByRole('textbox', { name: 'Name' }), ' Group')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Rename' }))
    await waitFor(() => expect(api.callsTo('PATCH /v1/admin/clients/1')).toHaveLength(1))
    expect(within(dialog).getByRole('button', { name: 'Cancel' }).getAttribute('disabled')).not.toBeNull()
    await userEvent.keyboard('{Escape}')
    expect(onClose).not.toHaveBeenCalled()
    const renamed = clientOf({ ...saltmarsh, name: 'Saltmarsh Shipping Ltd Group' })
    await act(async () => slow.answer(renamed))
    expect(onSaved).toHaveBeenCalledWith(renamed)
    expect(onClose).toHaveBeenCalledTimes(1)
  })
})
