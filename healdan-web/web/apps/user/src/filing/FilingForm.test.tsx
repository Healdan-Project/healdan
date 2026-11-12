// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { expectNoAxeViolations } from '../test/axe'
import { field, page } from '../test/fakeApi'
import { renderApp } from '../test/renderApp'
import * as t from './testing'
const { emptyForm, file, data, dropWithOption, routes, grid, tray } = t

describe('The form that comes first', () => {
  it("shows Client and Matter as the Edit form does, from the folder's matter, and no Message-ID", async () => {
    const form = page([
      ...emptyForm.items,
      field({ name: 'matter', label: 'Matter', value: '1' }),
      field({ name: 'emailmessageid', label: 'Message-ID', editable: true, value: null }),
    ])
    const { all } = routes({}, { form })
    await renderApp('/folders/7', all)
    fireEvent.drop(await grid(), { dataTransfer: data([file('Lease.docx')]) })
    const dialog = await screen.findByRole('dialog', { name: 'File Documents' })
    expect(within(dialog).getByText('Client').nextElementSibling?.textContent).toBe('1001 – Saltmarsh Shipping Ltd')
    expect(within(dialog).getByText('Matter').nextElementSibling?.textContent).toBe('1001.1 – Charter Dispute')
    expect(within(dialog).queryByText('Message-ID')).toBeNull()
  })

  it('shows the form first when a required field has no default, with the files\' titles and one form, and its button counts the files', async () => {
    const { all, sent } = routes({}, { form: emptyForm })
    await renderApp('/folders/7', all)
    fireEvent.drop(await grid(), { dataTransfer: data([file('Lease.docx'), file('Plan.pdf'), file('Note.txt')]) })

    const dialog = await screen.findByRole('dialog', { name: 'File Documents' })
    expect(sent).toHaveLength(0)
    expect((within(dialog).getByRole('textbox', { name: 'Title of Plan.pdf' }) as HTMLInputElement).value).toBe('Plan.pdf')
    await userEvent.clear(within(dialog).getByRole('textbox', { name: 'Title of Plan.pdf' }))
    await userEvent.type(within(dialog).getByRole('textbox', { name: 'Title of Plan.pdf' }), 'Site Plan.pdf')
    await userEvent.selectOptions(within(dialog).getByRole('combobox', { name: /Class/ }), 'Invoices')
    await userEvent.click(within(dialog).getByRole('button', { name: 'File 3 Items' }))

    await within(await tray()).findByText('Filed as # 10502')
    expect(sent.map((one) => [one.name, one.properties])).toEqual([
      ['Lease.docx', { docclass: 'Invoices' }],
      ['Site Plan.pdf', { docclass: 'Invoices' }],
      ['Note.txt', { docclass: 'Invoices' }],
    ])
    expect(screen.queryByRole('dialog')).toBeNull()
  })


  it('marks the required field of the form and files nothing until it has a value', async () => {
    const { all, sent } = routes({}, { form: emptyForm })
    await renderApp('/folders/7', all)
    fireEvent.drop(await grid(), { dataTransfer: data([file('Lease.docx')]) })
    const dialog = await screen.findByRole('dialog', { name: 'File Documents' })
    expect(within(dialog).getByRole('button', { name: 'File 1 Item' })).toBeTruthy()
    expect(within(dialog).getByText('*')).toBeTruthy()
    await expectNoAxeViolations(dialog)
    // Nothing is chosen for Class, which the form's first option shows; a title with no name is the other way to stop
    await userEvent.clear(within(dialog).getByRole('textbox', { name: 'Title of Lease.docx' }))
    await userEvent.click(within(dialog).getByRole('button', { name: 'File 1 Item' }))
    // Both the empty title and the Class that has no value are marked
    expect(await within(dialog).findAllByText('This needs a value.')).toHaveLength(2)
    expect(sent).toHaveLength(0)
  })


  it('files a required yes-or-no with no value, left unticked, as No', async () => {
    const form = page([...t.filledForm.items, field({ name: 'urgent', label: 'Urgent', type: 'boolean', required: true, editable: true, value: null })])
    const { all, sent } = routes({}, { form })
    await renderApp('/folders/7', all)
    fireEvent.drop(await grid(), { dataTransfer: data([file('Lease.docx')]) })
    const dialog = await screen.findByRole('dialog', { name: 'File Documents' })
    expect((within(dialog).getByRole('checkbox', { name: /Urgent/ }) as HTMLInputElement).checked).toBe(false)
    await userEvent.click(within(dialog).getByRole('button', { name: 'File 1 Item' }))

    await within(await tray()).findByText('Filed as # 10500')
    expect(sent.map((one) => one.properties)).toEqual([{ urgent: false }])
  })

  it('shows the form first when Option is held, though every field has a default', async () => {
    const { all, sent } = routes()
    await renderApp('/folders/7', all)
    dropWithOption(await grid(), [file('Lease.docx')])
    const dialog = await screen.findByRole('dialog', { name: 'File Documents' })
    expect(sent).toHaveLength(0)
    await userEvent.click(within(dialog).getByRole('button', { name: 'File 1 Item' }))
    await within(await tray()).findByText('Filed as # 10500')
    expect(sent[0]).toMatchObject({ name: 'Lease.docx', properties: null })
  })


  const bool = (value: boolean | null) => page([
    field({ name: 'client', label: 'Client', value: '1001' }),
    field({ name: 'signed', label: 'Signed', type: 'boolean', required: true, editable: true, value, source: value === null ? null : 'default' }),
  ])

  it('files a required yes-or-no that defaults to No, and a required number that defaults to 0, though they are falsy', async () => {
    const numbers = page([
      field({ name: 'signed', label: 'Signed', type: 'boolean', required: true, editable: true, value: false, source: 'default' }),
      field({ name: 'pages', label: 'Pages', type: 'integer', required: true, editable: true, value: 0, source: 'default' }),
    ])
    const { all, sent } = routes({}, { form: numbers })
    await renderApp('/folders/7', all)
    // Without Option nothing is missing, so it files at once
    fireEvent.drop(await grid(), { dataTransfer: data([file('A.pdf')]) })
    await within(await tray()).findByText('Filed as # 10500')
    // With Option the form comes, and files as well
    dropWithOption(await grid(), [file('B.pdf')])
    await userEvent.click(within(await screen.findByRole('dialog', { name: 'File Documents' })).getByRole('button', { name: 'File 1 Item' }))
    await within(await tray()).findByText('Filed as # 10501')
    expect(sent).toHaveLength(2)
  })

  it('marks a required default the person cleared in the form', async () => {
    const withDefault = page([field({ name: 'comments', label: 'Comments', required: true, editable: true, value: 'Chase', source: 'default' })])
    const { all, sent } = routes({}, { form: withDefault })
    await renderApp('/folders/7', all)
    dropWithOption(await grid(), [file('A.pdf')])
    const dialog = await screen.findByRole('dialog', { name: 'File Documents' })
    await userEvent.clear(within(dialog).getByRole('textbox', { name: /Comments/ }))
    await userEvent.click(within(dialog).getByRole('button', { name: 'File 1 Item' }))
    expect(await within(dialog).findByText('This needs a value.')).toBeTruthy()
    expect(sent).toHaveLength(0)
    void bool
  })

  it('shows no title field for an email, which takes its subject, and says so', async () => {
    const { all, sent } = routes()
    await renderApp('/folders/7', all)
    dropWithOption(await grid(), [file('Re Lease.eml'), file('Plan.pdf')])
    const dialog = await screen.findByRole('dialog', { name: 'File Documents' })
    expect(within(dialog).queryByRole('textbox', { name: 'Title of Re Lease.eml' })).toBeNull()
    expect(within(dialog).getByRole('textbox', { name: 'Title of Plan.pdf' })).toBeTruthy()
    expect(within(dialog).getByText('Titled from its subject')).toBeTruthy()
    await userEvent.click(within(dialog).getByRole('button', { name: 'File 2 Items' }))
    await within(await tray()).findByText('Filed as # 10501')
    expect(sent.map((one) => one.name)).toEqual(['Re Lease.eml', 'Plan.pdf'])
  })

  it('keeps the files of a first drop when a second needs the form too: its form opens when the first closes', async () => {
    const { all, sent } = routes({}, { form: emptyForm })
    const { api } = await renderApp('/folders/7', all)
    const list = await grid()
    fireEvent.drop(list, { dataTransfer: data([file('First.pdf')]) })
    await screen.findByRole('dialog', { name: 'File Documents' })
    // The first form is modal and hides the page, so the second drop is made on the list as it was
    fireEvent.drop(list, { dataTransfer: data([file('Second.pdf')]) })
    await waitFor(() => expect(api.callsTo('GET /v1/folders/7/form')).toHaveLength(2))
    const first = await screen.findByRole('dialog', { name: 'File Documents' })
    expect(within(first).getByRole('textbox', { name: 'Title of First.pdf' })).toBeTruthy()
    await userEvent.selectOptions(within(first).getByRole('combobox', { name: /Class/ }), 'Letters')
    await userEvent.click(within(first).getByRole('button', { name: 'File 1 Item' }))
    const second = await screen.findByRole('dialog', { name: 'File Documents' })
    expect(within(second).getByRole('textbox', { name: 'Title of Second.pdf' })).toBeTruthy()
    await userEvent.click(within(second).getByRole('button', { name: 'Cancel' }))
    expect(sent.map((one) => one.name)).toEqual(['First.pdf'])
  })
})

