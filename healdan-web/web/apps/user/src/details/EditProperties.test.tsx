// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { ApiError, type components } from '@healdan/api'
import { expectNoAxeViolations } from '../test/axe'
import { detailsRoutes, letter, propertiesOf } from '../test/details'
import { children, document, field, folder, page, type Routes } from '../test/fakeApi'
import { renderApp } from '../test/renderApp'

type S = components['schemas']

const correspondence = folder({ id: 7, name: 'Correspondence' })

const classes = [
  { value: 'Letters', label: 'Letters', class: null },
  { value: 'Invoices', label: 'Invoices', class: null },
]
const subclasses = [
  { value: 'Incoming', label: 'Incoming', class: 'Letters' },
  { value: 'Outgoing', label: 'Outgoing', class: 'Letters' },
  { value: 'Paid', label: 'Paid', class: 'Invoices' },
]

/** The profile as a person who may change it sees it: everything editable but what comes from the matter and the message */
function profile(over: Record<string, Partial<S['ProfileField']>> = {}, editable = true): S['ProfileField'][] {
  const base = propertiesOf()
  const extra = [
    field({ name: 'reviewdate', label: 'Review Date', type: 'date', value: '2026-11-02T00:00:00Z', source: 'local' }),
    field({ name: 'pages', label: 'Pages', type: 'integer', value: 12, source: 'local' }),
    field({ name: 'signed', label: 'Signed', type: 'boolean', value: false, source: 'local' }),
  ]
  return [...base, ...extra].map((one) => {
    const mine: Partial<S['ProfileField']> = { ...(one.name === 'docclass' ? { required: true, options: classes, source: 'inherited' as const, inheritedFrom: { id: 7, name: 'Correspondence', kind: 'folder' as const } } : {}), ...(one.name === 'docsubclass' ? { options: subclasses, source: 'inherited' as const } : {}), ...over[one.name] }
    return { ...one, editable: editable && !['emailfrom', 'emailto', 'emailcc', 'emailsubject', 'emailsent', 'emailreceived'].includes(one.name), ...mine }
  })
}

function routes(fields: S['ProfileField'][], over: Routes = {}): Routes {
  return {
    'GET /v1/folders/7': correspondence,
    'GET /v1/folders/7/children': children([letter]),
    ...detailsRoutes(letter, { 'GET /v1/documents/10388/properties': page(fields) }),
    ...over,
  }
}

const details = () => screen.findByRole('region', { name: 'Details' })
async function edit() {
  const panel = await details()
  const button = await within(panel).findByRole('button', { name: 'Edit' })
  await waitFor(() => expect((button as HTMLButtonElement).disabled).toBe(false))
  await userEvent.click(button)
  return panel
}

describe('Editing properties', () => {
  it('shows the form from the properties, with the required ones marked', async () => {
    await renderApp('/folders/7?sel=10388', routes(profile()))
    const panel = await edit()

    expect((within(panel).getByRole('combobox', { name: /Class/ }) as HTMLSelectElement).required).toBe(true)
    expect(within(panel).getByText('*')).toBeTruthy()
    expect((within(panel).getByRole('textbox', { name: 'Comments' }) as HTMLInputElement).value).toBe('Chase on Monday')
    expect((within(panel).getByRole('spinbutton', { name: 'Pages' }) as HTMLInputElement).value).toBe('12')
    expect((within(panel).getByRole('checkbox', { name: 'Signed' }) as HTMLInputElement).checked).toBe(false)
    expect(within(panel).getByRole('spinbutton', { name: /month/i }).textContent).toBe('11')
    await expectNoAxeViolations(panel)
  })

  it('offers a property picked from a list only its options, and a Subclass only its Class\'s', async () => {
    await renderApp('/folders/7?sel=10388', routes(profile()))
    const panel = await edit()

    const classBox = within(panel).getByRole('combobox', { name: /Class/ })
    // Class is required, so it has no empty choice; Subclass may be empty
    expect(within(classBox).getAllByRole('option').map((option) => option.textContent)).toEqual(['Letters', 'Invoices'])
    const subclassBox = within(panel).getByRole('combobox', { name: 'Subclass' })
    expect(within(subclassBox).getAllByRole('option').map((option) => option.textContent)).toEqual(['None', 'Incoming', 'Outgoing'])

    await userEvent.selectOptions(classBox, 'Invoices')
    expect(within(subclassBox).getAllByRole('option').map((option) => option.textContent)).toEqual(['None', 'Paid'])
    // The Subclass that was Incoming belonged to Letters, and is cleared
    expect((subclassBox as HTMLSelectElement).value).toBe('')
  })

  it('never edits Client and Matter, nor a protected document\'s fields', async () => {
    await renderApp('/folders/7?sel=10388', routes(profile({ client: { editable: true }, matter: { editable: true } })))
    const panel = await edit()
    expect(within(panel).queryByRole('textbox', { name: /Client/ })).toBeNull()
    expect(within(panel).queryByRole('textbox', { name: /Matter/ })).toBeNull()
    expect(within(panel).getByText('Client').nextElementSibling?.textContent).toBe('1001 – Saltmarsh Shipping Ltd')
    expect(within(panel).getByText('Matter').nextElementSibling?.textContent).toBe('1001.1 – Charter Dispute')
  })

  it('has Edit disabled when the API says none of the properties may be changed', async () => {
    await renderApp('/folders/7?sel=10388', routes(profile({}, false)))
    const panel = await details()
    await within(panel).findAllByRole('term')
    expect((within(panel).getByRole('button', { name: 'Edit' }) as HTMLButtonElement).disabled).toBe(true)
  })

  it('saves only what changed, and shows the saved properties', async () => {
    let patched: unknown
    let fields = profile()
    const { api } = await renderApp(
      '/folders/7?sel=10388',
      routes(profile(), {
        'GET /v1/documents/10388/properties': () => page(fields),
        'PATCH /v1/documents/10388/properties': ({ body }: { body?: unknown }) => {
          patched = body
          fields = profile({ comments: { value: 'Chase on Tuesday' } })
          return page(fields)
        },
      }),
    )
    const panel = await edit()
    const comments = within(panel).getByRole('textbox', { name: 'Comments' })
    await userEvent.clear(comments)
    await userEvent.type(comments, 'Chase on Tuesday')
    await userEvent.click(within(panel).getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(within(panel).queryByRole('button', { name: 'Save' })).toBeNull())
    expect(patched).toEqual({ values: { comments: 'Chase on Tuesday' } })
    expect(api.callsTo('PATCH /v1/documents/10388/properties')).toHaveLength(1)
  })

  it('sends a changed date, number and yes-no as the API takes them, and clears a property that was emptied', async () => {
    let patched: unknown
    await renderApp(
      '/folders/7?sel=10388',
      routes(profile(), {
        'PATCH /v1/documents/10388/properties': ({ body }: { body?: unknown }) => {
          patched = body
          return page(profile())
        },
      }),
    )
    const panel = await edit()
    const pages = within(panel).getByRole('spinbutton', { name: 'Pages' })
    await userEvent.clear(pages)
    await userEvent.type(pages, '14.5')
    await userEvent.click(within(panel).getByRole('checkbox', { name: 'Signed' }))
    await userEvent.clear(within(panel).getByRole('textbox', { name: 'Legacy Doc #' }))
    await userEvent.click(within(panel).getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(patched).toEqual({ values: { pages: 14.5, signed: true, legacydocnum: null } }))
  })

  it('saves nothing when nothing changed, and Cancel leaves the properties as they were', async () => {
    const { api } = await renderApp('/folders/7?sel=10388', routes(profile()))
    let panel = await edit()
    await userEvent.click(within(panel).getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(within(panel).queryByRole('button', { name: 'Save' })).toBeNull())
    expect(api.callsTo('PATCH /v1/documents/10388/properties')).toHaveLength(0)

    panel = await edit()
    await userEvent.type(within(panel).getByRole('textbox', { name: 'Comments' }), ' more')
    await userEvent.click(within(panel).getByRole('button', { name: 'Cancel' }))
    expect(within(panel).queryByRole('textbox')).toBeNull()
    expect(api.callsTo('PATCH /v1/documents/10388/properties')).toHaveLength(0)
  })

  it('marks each field a 422 names with its message from the catalogue, and keeps the form', async () => {
    const refused = new ApiError(422, 'validation', 'English from the server', {
      code: 'validation',
      fields: [
        { field: 'comments', code: 'too-long', detail: 'English from the server' },
        { field: 'pages', code: 'type', detail: 'English from the server' },
      ],
    })
    await renderApp('/folders/7?sel=10388', routes(profile(), { 'PATCH /v1/documents/10388/properties': refused }))
    const panel = await edit()
    await userEvent.type(within(panel).getByRole('textbox', { name: 'Comments' }), '!')
    await userEvent.type(within(panel).getByRole('spinbutton', { name: 'Pages' }), '1')
    await userEvent.click(within(panel).getByRole('button', { name: 'Save' }))

    const comments = within(panel).getByRole('textbox', { name: 'Comments' })
    await waitFor(() => expect(comments.getAttribute('aria-invalid')).toBe('true'))
    expect(within(panel).getByText('That is too long.')).toBeTruthy()
    expect(within(panel).getByText("That isn't the right kind of value.")).toBeTruthy()
    expect(within(panel).queryByText(/English from the server/)).toBeNull()
    expect(within(panel).getByRole('button', { name: 'Save' })).toBeTruthy()
    await expectNoAxeViolations(panel)
  })

  it("shows the server's problem from the catalogue when a hold refuses the save", async () => {
    await renderApp('/folders/7?sel=10388', routes(profile(), { 'PATCH /v1/documents/10388/properties': new ApiError(409, 'checked-out', 'English from the server', {}) }))
    const panel = await edit()
    await userEvent.type(within(panel).getByRole('textbox', { name: 'Comments' }), '!')
    await userEvent.click(within(panel).getByRole('button', { name: 'Save' }))
    expect(await screen.findByText("It's checked out.")).toBeTruthy()
    expect(within(panel).getByRole('button', { name: 'Save' })).toBeTruthy()
  })

  it('shows an override as Overridden with Follow Folder, which sends null for it', async () => {
    let patched: unknown
    const fields = profile({ docclass: { source: 'local', inheritedFrom: null } })
    await renderApp(
      '/folders/7?sel=10388',
      routes(fields, {
        'PATCH /v1/documents/10388/properties': ({ body }: { body?: unknown }) => {
          patched = body
          return page(fields)
        },
      }),
    )
    const panel = await edit()
    // Comments is local too, but it belongs to the one document and doesn't pass down: not an override
    expect(within(panel).getAllByText('Overridden').length).toBeGreaterThan(0)
    expect(within(panel).queryByRole('button', { name: 'Follow Folder: Comments' })).toBeNull()
    expect(within(panel).queryByRole('button', { name: 'Follow Folder: Subclass' })).toBeNull()
    await userEvent.click(within(panel).getByRole('button', { name: 'Follow Folder: Class' }))
    expect(within(panel).getByText('Follows the folder.')).toBeTruthy()
    expect(within(panel).queryByRole('combobox', { name: /Class/ })).toBeNull()
    await userEvent.click(within(panel).getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(patched).toEqual({ values: { docclass: null } }))
  })

  it('shows no Overridden for a value the document inherits', async () => {
    await renderApp('/folders/7?sel=10388', routes(profile({ reviewdate: { source: 'inherited' }, pages: { source: 'inherited' }, signed: { source: 'inherited' } })))
    const panel = await edit()
    expect(within(panel).queryByText('Overridden')).toBeNull()
  })

  it('makes a date from the picker the document\'s own', async () => {
    let patched: unknown
    await renderApp(
      '/folders/7?sel=10388',
      routes(profile(), {
        'PATCH /v1/documents/10388/properties': ({ body }: { body?: unknown }) => {
          patched = body
          return page(profile())
        },
      }),
    )
    const panel = await edit()
    await userEvent.click(within(panel).getByRole('spinbutton', { name: /day/i }))
    await userEvent.keyboard('05')
    await userEvent.click(within(panel).getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(patched).toEqual({ values: { reviewdate: '2026-11-05T00:00:00Z' } }))
  })
})

void document
