// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { cleanup, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { ApiError, type components } from '@healdan/api'
import { expectNoAxeViolations } from '../test/axe'
import { detailsRoutes, letter, propertiesOf } from '../test/details'
import { children, document, field, folder, page, type Routes } from '../test/fakeApi'
import { renderApp } from '../test/renderApp'
import { setViewport } from '../test/viewport'

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
    expect((within(panel).getByRole('textbox', { name: 'Pages' }) as HTMLInputElement).value).toBe('12')
    expect((within(panel).getByRole('checkbox', { name: 'Signed' }) as HTMLInputElement).checked).toBe(false)
    expect(within(panel).getByRole('spinbutton', { name: /month/i }).textContent).toBe('11')
    await expectNoAxeViolations(panel)
  })

  it('shows a date it may not change as the day the form would edit, not a time in the person\'s zone', async () => {
    // A day is kept as its UTC midnight, as the form and the helper write it: shown as a time, it would be the day before
    // anywhere west of UTC
    await renderApp('/folders/7?sel=10388', routes(profile({ reviewdate: { editable: false } })))
    const panel = await edit()
    expect(within(panel).getByText('Nov 2, 2026')).toBeTruthy()
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

  it('leaves the Message-ID out of the form, for an email and for a document that is none', async () => {
    const withId = (value: string | null) => [...profile(), field({ name: 'emailmessageid', label: 'Message-ID', type: 'string', editable: true, value, source: 'local' })]
    for (const value of [null, 'abc123@mail.example']) {
      await renderApp('/folders/7?sel=10388', routes(withId(value)))
      const panel = await edit()
      expect(within(panel).queryByText('Message-ID')).toBeNull()
      expect(within(panel).queryByRole('textbox', { name: /Message-ID/ })).toBeNull()
      cleanup()
    }
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

  it('changes the Author by a picker that asks for people as they are typed, and sends the person picked', async () => {
    let patched: unknown
    const asked: unknown[] = []
    await renderApp(
      '/folders/7?sel=10388',
      routes(profile(), {
        'GET /v1/users': ({ query }: { query?: Record<string, unknown> }) => {
          asked.push(query?.q)
          return page([
            { id: 7, username: 'pat', name: 'Pat Example' },
            { id: 3, username: 'jclarke', name: 'Jamie Clarke' },
          ])
        },
        'PATCH /v1/documents/10388/properties': ({ body }: { body?: unknown }) => {
          patched = body
          return page(profile({ author: { value: { id: 7, name: 'Pat Example' } } }))
        },
      }),
    )
    const panel = await edit()
    const author = within(panel).getByRole('combobox', { name: 'Author' }) as HTMLInputElement
    // The field's person is its value
    expect(author.value).toBe('Jamie Clarke')
    // By keyboard alone: type, an arrow to the person, Enter
    await userEvent.clear(author)
    await userEvent.type(author, 'pa')
    const listbox = await screen.findByRole('listbox')
    expect(await within(listbox).findByRole('option', { name: 'Pat Example' })).toBeTruthy()
    // The field's own person is offered first, and nobody twice
    expect(within(listbox).getAllByRole('option').map((one) => one.textContent)).toEqual(['Jamie Clarke', 'Pat Example'])
    expect(asked.at(-1)).toBe('pa')
    await userEvent.keyboard('{ArrowDown}{ArrowDown}{Enter}')
    expect(author.value).toBe('Pat Example')
    await userEvent.click(within(panel).getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(patched).toEqual({ values: { author: { id: 7 } } }))
  })

  it('asks for nobody until something is typed, and puts the person back when the text is left half typed', async () => {
    let patched: unknown = 'nothing'
    const { api } = await renderApp(
      '/folders/7?sel=10388',
      routes(profile(), {
        'GET /v1/users': page([{ id: 7, username: 'pat', name: 'Pat Example' }]),
        'PATCH /v1/documents/10388/properties': ({ body }: { body?: unknown }) => {
          patched = body
          return page(profile())
        },
      }),
    )
    const panel = await edit()
    const typist = within(panel).getByRole('combobox', { name: 'Typist' }) as HTMLInputElement
    await userEvent.clear(typist)
    expect(api.callsTo('GET /v1/users')).toEqual([])
    await userEvent.type(typist, 'p')
    await screen.findByRole('option', { name: 'Pat Example' })
    expect(api.callsTo('GET /v1/users').at(-1)?.query).toEqual({ q: 'p' })
    await userEvent.tab()
    expect(typist.value).toBe('Priya Nair')
    await userEvent.click(within(panel).getByRole('button', { name: 'Save' }))
    // Nothing changed, so nothing is sent
    await waitFor(() => expect(within(panel).queryByRole('button', { name: 'Save' })).toBeNull())
    expect(patched).toBe('nothing')
  })

  it('clears a user property that was emptied, and marks a required one the server refuses empty', async () => {
    let patched: unknown
    await renderApp(
      '/folders/7?sel=10388',
      routes(profile({ author: { required: true } }), {
        'PATCH /v1/documents/10388/properties': ({ body }: { body?: unknown }) => {
          patched = body
          throw new ApiError(422, 'validation', 'Author needs a value.', {
            status: 422,
            code: 'validation',
            detail: 'Author needs a value.',
            fields: [{ field: 'author', code: 'required', detail: 'Author needs a value.' }],
          })
        },
      }),
    )
    const panel = await edit()
    const author = within(panel).getByRole('combobox', { name: /Author/ }) as HTMLInputElement
    expect(author.getAttribute('aria-required')).toBe('true')
    await userEvent.clear(author)
    await userEvent.tab()
    expect(author.value).toBe('')
    await userEvent.click(within(panel).getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(patched).toEqual({ values: { author: null } }))
    expect(await within(panel).findByText('This needs a value.')).toBeTruthy()
  })

  it("saves against what the form opened with, so a refetch while it's open doesn't send what someone else changed", async () => {
    let patched: unknown
    let fields = profile()
    let doc = letter
    await renderApp(
      '/folders/7?sel=10388',
      routes(profile(), {
        'GET /v1/documents/10388': () => doc,
        'GET /v1/documents/10388/properties': () => page(fields),
        // The check-out asks for the properties again while the form is open
        'POST /v1/documents/10388/checkout': () => {
          fields = profile({ legacydocnum: { value: 'LEG-2' }, pages: { value: 99 } })
          return (doc = document({ ...letter, hold: { kind: 'checkout', by: { id: 5, name: 'Priya Nair' }, since: null, device: null } }))
        },
        'PATCH /v1/documents/10388/properties': ({ body }: { body?: unknown }) => {
          patched = body
          return page(fields)
        },
      }),
    )
    const panel = await edit()
    await userEvent.type(within(panel).getByRole('textbox', { name: 'Comments' }), '!')
    await userEvent.click(within(panel).getByRole('button', { name: 'Check Out' }))
    await waitFor(() => expect(within(panel).getByText(/Checked out by you/)).toBeTruthy())
    await userEvent.click(within(panel).getByRole('button', { name: 'Save' }))

    // Only Comments, which the person edited: not Legacy Doc # or Pages, which changed under the form
    await waitFor(() => expect(patched).toEqual({ values: { comments: 'Chase on Monday!' } }))
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
    const pages = within(panel).getByRole('textbox', { name: 'Pages' })
    await userEvent.clear(pages)
    await userEvent.type(pages, '14')
    await userEvent.click(within(panel).getByRole('checkbox', { name: 'Signed' }))
    await userEvent.click(within(panel).getByRole('spinbutton', { name: /day/i }))
    await userEvent.keyboard('05')
    await userEvent.clear(within(panel).getByRole('textbox', { name: 'Legacy Doc #' }))
    await userEvent.click(within(panel).getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(patched).toEqual({ values: { reviewdate: '2026-11-05T00:00:00Z', pages: 14, signed: true, legacydocnum: null } }))
  })

  it('shows a number field that is not a number as an error, and sends nothing, instead of clearing it', async () => {
    const { api } = await renderApp('/folders/7?sel=10388', routes(profile()))
    const panel = await edit()
    const pages = within(panel).getByRole('textbox', { name: 'Pages' })
    await userEvent.clear(pages)
    await userEvent.type(pages, '12x')
    await userEvent.click(within(panel).getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(pages.getAttribute('aria-invalid')).toBe('true'))
    expect(within(panel).getByText("That isn't the right kind of value.")).toBeTruthy()
    // An integer isn't a decimal either
    await userEvent.clear(pages)
    await userEvent.type(pages, '1.5')
    await userEvent.click(within(panel).getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(pages.getAttribute('aria-invalid')).toBe('true'))
    expect(api.callsTo('PATCH /v1/documents/10388/properties')).toHaveLength(0)
  })

  it("leaves a property the API says can't be changed as text, even in a form that can change the rest", async () => {
    await renderApp('/folders/7?sel=10388', routes(profile({ pages: { editable: false }, signed: { editable: false } })))
    const panel = await edit()
    expect(within(panel).queryByRole('textbox', { name: 'Pages' })).toBeNull()
    expect(within(panel).queryByRole('checkbox', { name: 'Signed' })).toBeNull()
    expect(within(panel).getByText('Pages').nextElementSibling?.textContent).toBe('12')
    // Comments, which can be changed, is still a field
    expect(within(panel).getByRole('textbox', { name: 'Comments' })).toBeTruthy()
  })

  it('marks a required date as the other required fields are, to a screen reader too', async () => {
    await renderApp('/folders/7?sel=10388', routes(profile({ reviewdate: { required: true }, signed: { required: true } })))
    const panel = await edit()
    expect(within(panel).getByRole('group', { name: /Review Date/ }).getAttribute('aria-required') ?? within(panel).getAllByRole('spinbutton', { name: /day/i })[0]!.getAttribute('aria-required')).toBe('true')
    // A required yes-or-no is answered either way, so No is fine: the box isn't a required one
    const signed = within(panel).getByRole('checkbox', { name: /Signed/ }) as HTMLInputElement
    expect(signed.required).toBe(false)
    expect(signed.closest('label')?.textContent).toContain('*')
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
    await userEvent.type(within(panel).getByRole('textbox', { name: 'Pages' }), '1')
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

  it('shows a value that takes the place of a folder\'s as Overridden with Follow Folder, which sends null for it', async () => {
    let patched: unknown
    const folderRef = { id: 7, name: 'Correspondence', kind: 'folder' as const }
    // Class overrides Correspondence's; Comments and Pages are local too, but no folder would supply them
    const fields = profile({ docclass: { source: 'local', inheritedFrom: null, overrides: folderRef } })
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
    expect(within(panel).getAllByText('Overridden')).toHaveLength(1)
    expect(within(panel).queryByRole('button', { name: 'Follow Folder: Comments' })).toBeNull()
    expect(within(panel).queryByRole('button', { name: 'Follow Folder: Pages' })).toBeNull()
    expect(within(panel).queryByRole('button', { name: 'Follow Folder: Subclass' })).toBeNull()
    await userEvent.click(within(panel).getByRole('button', { name: 'Follow Folder: Class' }))
    expect(within(panel).getByText('Follows the folder.')).toBeTruthy()
    expect(within(panel).queryByRole('combobox', { name: /Class/ })).toBeNull()
    await userEvent.click(within(panel).getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(patched).toEqual({ values: { docclass: null } }))
  })

  it('shows a plain local value as a plain value, which clearing it clears', async () => {
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
    expect(within(panel).queryByText('Overridden')).toBeNull()
    await userEvent.clear(within(panel).getByRole('textbox', { name: 'Comments' }))
    await userEvent.click(within(panel).getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(patched).toEqual({ values: { comments: null } }))
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

  it('cancels the form with one Esc and leaves the folded region open in a narrow window, the focus on Edit', async () => {
    setViewport(768)
    await renderApp('/folders/7?sel=10388', routes(profile()))
    await userEvent.click(await screen.findByRole('button', { name: 'Details and Filters' }))
    const panel = await details()
    const button = await within(panel).findByRole('button', { name: 'Edit' })
    await waitFor(() => expect((button as HTMLButtonElement).disabled).toBe(false))
    button.focus()
    await userEvent.keyboard('{Enter}')
    await within(panel).findByRole('button', { name: 'Save' })
    await userEvent.keyboard('{Escape}')
    await waitFor(() => expect(within(panel).queryByRole('button', { name: 'Save' })).toBeNull())
    expect(screen.queryByRole('region', { name: 'Details' })).toBe(panel)
    await waitFor(() => expect(globalThis.document.activeElement).toBe(button))
  })

  describe('by keyboard', () => {
    // Edit is pressed with Enter, as a person who doesn't point does
    async function editByKey() {
      const panel = await details()
      const button = await within(panel).findByRole('button', { name: 'Edit' })
      await waitFor(() => expect((button as HTMLButtonElement).disabled).toBe(false))
      button.focus()
      await userEvent.keyboard('{Enter}')
      return { panel, button }
    }

    it('puts the focus in the first field when Edit is pressed, so the keys go on from there', async () => {
      await renderApp('/folders/7?sel=10388', routes(profile()))
      const { panel } = await editByKey()
      await waitFor(() => expect(panel.contains(globalThis.document.activeElement)).toBe(true))
      expect(globalThis.document.activeElement?.closest('form')).toBeTruthy()
      expect(['INPUT', 'SELECT']).toContain(globalThis.document.activeElement?.tagName)
    })

    it('cancels on Esc, and gives the focus back to Edit', async () => {
      await renderApp('/folders/7?sel=10388', routes(profile()))
      const { panel, button } = await editByKey()
      await waitFor(() => expect(within(panel).queryByRole('button', { name: 'Save' })).toBeTruthy())
      await userEvent.keyboard('{Escape}')
      await waitFor(() => expect(within(panel).queryByRole('button', { name: 'Save' })).toBeNull())
      await waitFor(() => expect(globalThis.document.activeElement).toBe(button))
    })

    it('gives the focus back to Edit after Save, and after Cancel', async () => {
      await renderApp('/folders/7?sel=10388', routes(profile(), { 'PATCH /v1/documents/10388/properties': () => page(profile()) }))
      const { panel, button } = await editByKey()
      await userEvent.click(within(panel).getByRole('button', { name: 'Cancel' }))
      await waitFor(() => expect(globalThis.document.activeElement).toBe(button))
      button.focus()
      await userEvent.keyboard('{Enter}')
      await userEvent.type(await within(panel).findByRole('textbox', { name: 'Comments' }), ' more')
      await userEvent.keyboard('{Enter}')
      await waitFor(() => expect(within(panel).queryByRole('button', { name: 'Save' })).toBeNull())
      await waitFor(() => expect(globalThis.document.activeElement).toBe(button))
    })
  })
})
