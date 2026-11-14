// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { ApiError } from '@healdan/api'
import { folder, page } from '../test/fakeApi'
import { renderApp } from '../test/renderApp'
import { drafts, grid, row, routes } from './testing'

const field = (name = 'Drafts') => screen.findByRole('textbox', { name: `New Name of ${name}` })

/** F2 on a row, as the keyboard sends it to the focused row (a click on a folder's row would open it) */
async function pressF2On(name: RegExp) {
  await pressF2OnRow(await row(name))
}

async function pressF2OnRow(target: HTMLElement) {
  target.focus()
  await userEvent.keyboard('{F2}')
}

describe('Renaming a folder in a list', () => {
  it('F2 on a folder row edits its name in place, all of it selected, and Enter saves it', async () => {
    const { all, written } = routes()
    const { api } = await renderApp('/folders/7', all)
    await pressF2On(/Drafts/)

    const input = (await field()) as HTMLInputElement
    expect(document.activeElement).toBe(input)
    expect([input.selectionStart, input.selectionEnd]).toEqual([0, 'Drafts'.length])
    await userEvent.keyboard('Final Drafts{Enter}')

    await waitFor(() => expect(written.renamedFolders).toEqual([{ id: 8, name: 'Final Drafts' }]))
    await waitFor(() => expect(screen.queryByRole('textbox')).toBeNull())
    expect(await row(/Final Drafts/)).toBeTruthy()
    await waitFor(() => expect(document.activeElement?.getAttribute('data-row-key')).toBe('f8'))
    // What shows folder names is asked for again
    expect(api.callsTo('GET /v1/folders/7/children').length).toBeGreaterThan(1)
    await waitFor(() => expect(api.callsTo('GET /v1/me/favorites').length).toBeGreaterThan(1))
  })

  it('Esc leaves the name as it was, with no call', async () => {
    const { all, written } = routes()
    await renderApp('/folders/7', all)
    await pressF2On(/Drafts/)
    await field()
    await userEvent.keyboard('Something else{Escape}')
    await waitFor(() => expect(screen.queryByRole('textbox')).toBeNull())
    expect(written.renamedFolders).toEqual([])
    await waitFor(() => expect(document.activeElement?.getAttribute('data-row-key')).toBe('f8'))
  })

  it('keeps the field open with a taken name said beside it', async () => {
    const { all } = routes({
      'PATCH /v1/folders/*': () => {
        throw new ApiError(409, 'name-taken', 'A server sentence that must not be shown.', { status: 409, code: 'name-taken' })
      },
    })
    await renderApp('/folders/7', all)
    await pressF2On(/Drafts/)
    await field()
    await userEvent.keyboard('Letters{Enter}')
    expect((await screen.findByRole('alert')).textContent).toContain("There's already a folder called Letters here.")
    expect(await field()).toBeTruthy()
  })

  it("F2 on a folder the person can't rename says why, and opens no field", async () => {
    const { all } = routes({ 'GET /v1/folders/7/children': () => page([{ ...drafts, canRename: false }]) })
    await renderApp('/folders/7', all)
    await pressF2On(/Drafts/)
    expect(await screen.findByText("You don't have permission to rename this folder.")).toBeTruthy()
    expect(screen.queryByRole('textbox')).toBeNull()
  })

  it('F2 renames a folder in Recent › Folders', async () => {
    const { all, written } = routes({ 'GET /v1/folders/recent': page([drafts]) })
    await renderApp('/recent/folders', all)
    const target = within(await grid()).getAllByRole('row').find((one) => one.getAttribute('data-row-key') === 'f8')!
    await pressF2OnRow(target)
    await field()
    await userEvent.keyboard('Final Drafts{Enter}')
    await waitFor(() => expect(written.renamedFolders).toEqual([{ id: 8, name: 'Final Drafts' }]))
  })

  it('F2 renames a folder in a Folders search', async () => {
    const { all, written } = routes({ 'GET /v1/search': page([folder({ id: 8, name: 'Drafts' })]) })
    await renderApp('/search?scope=folders&q=drafts', all)
    const target = within(await grid()).getAllByRole('row').find((one) => one.getAttribute('data-row-key') === 'f8')!
    await pressF2OnRow(target)
    await field()
    await userEvent.keyboard('Final Drafts{Enter}')
    await waitFor(() => expect(written.renamedFolders).toEqual([{ id: 8, name: 'Final Drafts' }]))
  })
})
