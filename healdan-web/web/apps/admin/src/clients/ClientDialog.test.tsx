// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { adminComponents } from '@healdan/api'
import { createQueryClient, createSignedOut, MessagesProvider, ServicesProvider } from '@healdan/shell'
import { adminKeys } from '../queries'
import { problemLookup } from '../strings'
import { clientOf, fakeApi, saltmarsh, type Routes } from '../test/fakeApi'
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
