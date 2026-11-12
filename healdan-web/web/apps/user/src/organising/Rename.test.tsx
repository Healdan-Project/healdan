// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { problem } from '../test/fakeApi'
import { renderApp } from '../test/renderApp'
import { ApiError } from '@healdan/api'
import { letter, row, routes, select, toolbar } from './testing'

const field = (name = 'Letter to Dunmere Pryce.docx') => screen.findByRole('textbox', { name: `New name of ${name}` })

describe('Renaming in place', () => {
  it('F2 edits the title in the row with the name, not its extension, selected, and Enter saves it', async () => {
    const { all, written } = routes()
    const { api } = await renderApp('/folders/7', all)
    await select(/Letter to Dunmere/)
    await userEvent.keyboard('{F2}')

    const input = (await field()) as HTMLInputElement
    expect(document.activeElement).toBe(input)
    expect(input.value).toBe('Letter to Dunmere Pryce.docx')
    expect([input.selectionStart, input.selectionEnd]).toEqual([0, 'Letter to Dunmere Pryce'.length])

    await userEvent.keyboard('Lease Cover{Enter}')
    await waitFor(() => expect(written.renamed).toEqual([{ docNumber: 10431, name: 'Lease Cover.docx' }]))
    // The field goes, the list asks again and the row has the focus
    await waitFor(() => expect(screen.queryByRole('textbox')).toBeNull())
    expect(await row(/Lease Cover\.docx/)).toBeTruthy()
    await waitFor(() => expect(document.activeElement?.getAttribute('data-row-key')).toBe('d10431'))
    expect(api.callsTo('GET /v1/folders/7/children').length).toBeGreaterThan(1)
  })

  it('Esc cancels without a call, and the row has the focus', async () => {
    const { all, written } = routes()
    await renderApp('/folders/7', all)
    await select(/Letter to Dunmere/)
    await userEvent.keyboard('{F2}')
    await field()
    await userEvent.keyboard('Something else{Escape}')

    await waitFor(() => expect(screen.queryByRole('textbox')).toBeNull())
    expect(written.renamed).toEqual([])
    expect(await row(/Letter to Dunmere Pryce\.docx/)).toBeTruthy()
    await waitFor(() => expect(document.activeElement?.getAttribute('data-row-key')).toBe('d10431'))
  })

  it('makes no call for the same name', async () => {
    const { all, written } = routes()
    await renderApp('/folders/7', all)
    await select(/Letter to Dunmere/)
    await userEvent.keyboard('{F2}')
    await field()
    await userEvent.keyboard('{Enter}')
    await waitFor(() => expect(screen.queryByRole('textbox')).toBeNull())
    expect(written.renamed).toEqual([])
  })

  it('keeps the field open with the refusal beside it, in the catalogue\'s words, and saves once it is mended', async () => {
    let refuse = true
    const { all, written } = routes({
      'PATCH /v1/documents/*': ({ body }: { body?: unknown }) => {
        if (refuse) {
          refuse = false
          const fields = [{ field: 'name', code: 'extension', detail: 'A server sentence that must not be shown raw.' }]
          throw new ApiError(422, 'validation', 'A server sentence that must not be shown raw.', { status: 422, code: 'validation', fields })
        }
        written.renamed.push({ docNumber: 10431, name: (body as { name: string }).name })
        return letter
      },
    })
    await renderApp('/folders/7', all)
    await select(/Letter to Dunmere/)
    await userEvent.keyboard('{F2}')
    await field()
    await userEvent.clear(await field())
    await userEvent.keyboard('Cover.pdf{Enter}')

    expect((await screen.findByRole('alert')).textContent).toBe("The name has to end in .docx, as the document's does.")
    const input = await field()
    expect(input.getAttribute('aria-invalid')).toBe('true')
    expect(document.activeElement).toBe(input)
    expect(screen.queryByText(/server sentence/)).toBeNull()

    await userEvent.clear(input)
    await userEvent.keyboard('Cover.docx{Enter}')
    await waitFor(() => expect(written.renamed).toEqual([{ docNumber: 10431, name: 'Cover.docx' }]))
    await waitFor(() => expect(screen.queryByRole('textbox')).toBeNull())
  })

  it('shows a hold as the refusal says it, from the catalogue', async () => {
    const { all } = routes({ 'PATCH /v1/documents/*': problem(409, 'checked-out') })
    await renderApp('/folders/7', all)
    await select(/Letter to Dunmere/)
    await userEvent.keyboard('{F2}')
    await field()
    await userEvent.keyboard('Other{Enter}')
    expect((await screen.findByRole('alert')).textContent).toBe("It's checked out.")
  })

  it('More › Rename does the same, and works only with one document selected', async () => {
    const { all } = routes()
    await renderApp('/folders/7', all)
    const bar = await toolbar()
    expect(within(bar).getByRole('button', { name: 'More' }).hasAttribute('disabled')).toBe(true)

    await select(/Lease/)
    await userEvent.click(within(bar).getByRole('button', { name: 'More' }))
    await userEvent.click(await screen.findByRole('menuitem', { name: 'Rename' }))
    expect(await field('Lease.docx')).toBeTruthy()
    await userEvent.keyboard('{Escape}')

    await select(/Lease/, /Plan/)
    await userEvent.click(within(bar).getByRole('button', { name: 'More' }))
    expect((await screen.findByRole('menuitem', { name: 'Rename' })).getAttribute('aria-disabled')).toBe('true')
  })

  it('says to select one document when F2 finds several', async () => {
    const { all, written } = routes()
    await renderApp('/folders/7', all)
    await select(/Lease/, /Plan/)
    await userEvent.keyboard('{F2}')
    expect(await screen.findByText('Select one document to rename it.')).toBeTruthy()
    expect(screen.queryByRole('textbox')).toBeNull()
    expect(written.renamed).toEqual([])
  })

  it('saves what was changed when the field is left, and a refusal showing cancels instead', async () => {
    let refuse = true
    const { all, written } = routes({
      'PATCH /v1/documents/*': ({ body }: { body?: unknown }) => {
        const name = (body as { name: string }).name
        if (refuse && name === 'Bad.pdf') throw new ApiError(422, 'validation', 'x', { code: 'validation', fields: [{ field: 'name', code: 'extension' }] })
        written.renamed.push({ docNumber: 10431, name })
        return letter
      },
    })
    await renderApp('/folders/7', all)
    // Leaving with a new name saves it
    await select(/Letter to Dunmere/)
    await userEvent.keyboard('{F2}')
    await userEvent.clear(await field())
    await userEvent.keyboard('Left Behind.docx')
    await userEvent.click(screen.getByRole('button', { name: 'Filters' }))
    await waitFor(() => expect(written.renamed).toEqual([{ docNumber: 10431, name: 'Left Behind.docx' }]))
    await waitFor(() => expect(screen.queryByRole('textbox', { name: /New name/ })).toBeNull())

    // Leaving while a refusal shows is a cancel: nothing more is asked
    await select(/Lease/)
    await userEvent.keyboard('{F2}')
    const input = await field('Lease.docx')
    await userEvent.clear(input)
    await userEvent.keyboard('Bad.pdf{Enter}')
    expect(await screen.findByRole('alert')).toBeTruthy()
    await userEvent.click(screen.getByRole('button', { name: 'Filters' }))
    await waitFor(() => expect(screen.queryByRole('textbox', { name: /New name/ })).toBeNull())
    expect(written.renamed).toHaveLength(1)
    refuse = false
  })

  it('makes the row being renamed undraggable, so selecting text in its field starts no drag of the row', async () => {
    const { all } = routes()
    await renderApp('/folders/7', all)
    await select(/Lease/)
    expect((await row(/Lease/)).getAttribute('draggable')).toBe('true')
    await userEvent.keyboard('{F2}')
    await field('Lease.docx')
    expect((await row(/Lease/)).getAttribute('draggable')).toBe('false')
    await userEvent.keyboard('{Escape}')
    await waitFor(() => expect(screen.getAllByRole('row', { name: /Lease/ })[0]!.getAttribute('draggable')).toBe('true'))
  })
})
