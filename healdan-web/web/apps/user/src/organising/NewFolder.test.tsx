// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { ApiError } from '@healdan/api'
import { expectNoAxeViolations } from '../test/axe'
import { children, folder, me } from '../test/fakeApi'
import { renderApp } from '../test/renderApp'
import { routes, store, toolbar, workspace } from './testing'

const search = folder({ id: 112, name: 'Recent', kind: 'searchFolder', path: [store, workspace, { id: 112, name: 'Recent', kind: 'searchFolder' }], canFile: false })

describe('New Folder', () => {
  it('is offered inside a matter\'s folders, and asks for a name', async () => {
    const { all, written } = routes()
    const { api } = await renderApp('/folders/7', all)
    await userEvent.click(within(await toolbar()).getByRole('button', { name: 'New Folder' }))

    const dialog = await screen.findByRole('dialog', { name: 'New Folder' })
    await expectNoAxeViolations(dialog)
    const create = within(dialog).getByRole('button', { name: 'Create' })
    expect(create.hasAttribute('disabled')).toBe(true)
    await userEvent.type(within(dialog).getByRole('textbox', { name: 'Name' }), 'Board Papers{Enter}')

    await waitFor(() => expect(written.made).toEqual([{ parentId: 7, name: 'Board Papers' }]))
    expect(await screen.findByText('Created Board Papers')).toBeTruthy()
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    // The open folder's list and the tree's are asked again
    await waitFor(() => expect(api.callsTo('GET /v1/folders/7/children').length).toBeGreaterThan(1))
  })

  it('is not offered at a matter\'s top level, which stays the administrators\'', async () => {
    const { all } = routes()
    await renderApp('/folders/110', all)
    await toolbar()
    expect(screen.queryByRole('button', { name: 'New Folder' })).toBeNull()
  })

  it('is offered at a matter\'s top level to an administrator, as M1 allows', async () => {
    const { all } = routes({ 'GET /v1/me': { ...me, admin: true } })
    await renderApp('/folders/110', all)
    expect(await within(await toolbar()).findByRole('button', { name: 'New Folder' })).toBeTruthy()
  })

  it('is not offered in a search folder, even to an administrator', async () => {
    const { all } = routes({ 'GET /v1/folders/112': search, 'GET /v1/folders/112/children': children([]), 'GET /v1/me': { ...me, admin: true } })
    await renderApp('/folders/112', all)
    await toolbar()
    expect(screen.queryByRole('button', { name: 'New Folder' })).toBeNull()
  })

  it('keeps the dialog open with what is wrong, from the catalogue, when the server refuses', async () => {
    const { all, written } = routes({
      'POST /v1/folders': new ApiError(422, 'validation', 'A server sentence that must not be shown raw.', {
        code: 'validation',
        fields: [{ field: 'name', code: 'too-long', detail: 'A server sentence that must not be shown raw.' }],
      }),
    })
    await renderApp('/folders/7', all)
    await userEvent.click(within(await toolbar()).getByRole('button', { name: 'New Folder' }))
    const dialog = await screen.findByRole('dialog', { name: 'New Folder' })
    await userEvent.type(within(dialog).getByRole('textbox', { name: 'Name' }), 'x{Enter}')

    expect(await within(dialog).findByText('That is too long.')).toBeTruthy()
    expect(screen.queryByText(/server sentence/)).toBeNull()
    expect(written.made).toEqual([])

    await userEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  })

  it('says a refusal of the folder itself in the catalogue\'s words', async () => {
    const { all } = routes({ 'POST /v1/folders': new ApiError(403, 'forbidden', 'A server sentence that must not be shown raw.', { code: 'forbidden' }) })
    await renderApp('/folders/7', all)
    await userEvent.click(within(await toolbar()).getByRole('button', { name: 'New Folder' }))
    const dialog = await screen.findByRole('dialog', { name: 'New Folder' })
    await userEvent.type(within(dialog).getByRole('textbox', { name: 'Name' }), 'Board{Enter}')
    expect(await within(dialog).findByText("You don't have permission to do that.")).toBeTruthy()
  })
})
