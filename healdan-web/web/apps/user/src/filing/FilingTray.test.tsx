// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { ApiError } from '@healdan/api'
import { expectNoAxeViolations } from '../test/axe'
import { detailsRoutes, propertiesOf } from '../test/details'
import { document, page, problem } from '../test/fakeApi'
import { renderApp } from '../test/renderApp'
import * as t from './testing'
const { inFolder, file, data, sentOf, routes, grid, tray } = t

describe('The tray, with emails', () => {
  it('says an email already in the matter is, with Show, and files the others', async () => {
    const copy = document({ docNumber: 10455, name: 'Re Lease.eml', folder: inFolder })
    const { all } = routes({ 'GET /v1/documents/10455': copy }, {
      refuse: (name) => (name.endsWith('.eml') ? new ApiError(409, 'already-filed', 'x', { code: 'already-filed', document: copy }) : undefined),
    })
    const { router } = await renderApp('/folders/7', all)
    fireEvent.drop(await grid(), { dataTransfer: data([file('Re Lease.eml'), file('Plan.pdf')]) })

    const region = await tray()
    await within(region).findByText('Already in this matter as # 10455')
    await within(region).findByText('Filed as # 10500')
    await userEvent.click(within(region).getByRole('button', { name: 'Show Re Lease.eml' }))
    await waitFor(() => expect(router.history.location.pathname).toMatch(/10455/))
  })


  it('answers a second drop of the same email with the same message, and files nothing twice', async () => {
    const copy = document({ docNumber: 10500, name: 'Re Lease.eml', folder: inFolder })
    let first = true
    const { all, sent } = routes({}, { refuse: () => undefined })
    all['POST /v1/documents'] = async ({ body }: { body?: unknown }) => {
      sent.push(await sentOf(body))
      if (first) {
        first = false
        return copy
      }
      throw new ApiError(409, 'already-filed', 'x', { code: 'already-filed', document: copy })
    }
    await renderApp('/folders/7', all)
    fireEvent.drop(await grid(), { dataTransfer: data([file('Re Lease.eml')]) })
    await within(await tray()).findByText('Filed as # 10500')
    fireEvent.drop(await grid(), { dataTransfer: data([file('Re Lease.eml')]) })
    await within(await tray()).findByText('Already in this matter as # 10500')
    expect(sent).toHaveLength(2)
  })

})

describe('The tray', () => {
  it('asks before the page is left while an upload runs, and not after', async () => {
    let finish: (value: unknown) => void = () => {}
    const { all } = routes({ 'POST /v1/documents': () => new Promise((resolve) => (finish = resolve)) })
    await renderApp('/folders/7', all)
    const idle = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(idle)
    expect(idle.defaultPrevented).toBe(false)

    fireEvent.drop(await grid(), { dataTransfer: data([file('Lease.docx')]) })
    await within(await tray()).findByText('Uploading…')
    const busy = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(busy)
    expect(busy.defaultPrevented).toBe(true)

    finish(document({ docNumber: 10500, name: 'Lease.docx', folder: inFolder }))
    await within(await tray()).findByText('Filed as # 10500')
    const after = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(after)
    expect(after.defaultPrevented).toBe(false)
  })

  it('goes on while the person moves to another view, and shows a refusal from the catalogue', async () => {
    const { all } = routes({}, { refuse: () => problem(413, 'too-large') })
    const { router } = await renderApp('/folders/7', all)
    fireEvent.drop(await grid(), { dataTransfer: data([file('Huge.zip')]) })
    await within(await tray()).findByText('That file is too large.')
    await router.navigate({ to: '/recent/$kind', params: { kind: 'documents' } })
    expect(within(await tray()).getByText('Huge.zip')).toBeTruthy()
  })

  it('names a refused field, and dismisses a finished item', async () => {
    const { all } = routes({}, {
      refuse: () => new ApiError(422, 'validation', 'x', { code: 'validation', fields: [{ field: 'name', code: 'disallowed', detail: 'x' }] }),
    })
    await renderApp('/folders/7', all)
    fireEvent.drop(await grid(), { dataTransfer: data([file('run.exe')]) })
    await within(await tray()).findByText("Name: Your administrator doesn't allow that kind of file.")
    await userEvent.click(screen.getByRole('button', { name: 'Dismiss run.exe' }))
    expect(screen.queryByRole('region', { name: 'Filing' })).toBeNull()
  })

  it('has Edit on a filed item: it selects the document and opens the details\' Edit', async () => {
    const editable = propertiesOf().map((one) => ({ ...one, editable: !['client', 'matter'].includes(one.name) }))
    const made = document({ docNumber: 10500, name: 'Lease.docx', folder: inFolder })
    const { all } = routes({ ...detailsRoutes(made, { 'GET /v1/documents/10500/properties': page(editable) }) })
    const { router } = await renderApp('/folders/7', all)
    fireEvent.drop(await grid(), { dataTransfer: data([file('Lease.docx')]) })
    await userEvent.click(await within(await tray()).findByRole('button', { name: 'Edit Lease.docx' }))
    await waitFor(() => expect(router.history.location.search).toContain('sel=10500'))
    const details = await screen.findByRole('region', { name: 'Details' })
    expect(await within(details).findByRole('button', { name: 'Save' })).toBeTruthy()
  })

  it('passes axe, with a filed item and a refused one', async () => {
    const { all } = routes({}, { refuse: (name) => (name === 'Huge.zip' ? problem(413, 'too-large') : undefined) })
    await renderApp('/folders/7', all)
    fireEvent.drop(await grid(), { dataTransfer: data([file('Lease.docx'), file('Huge.zip')]) })
    const region = await tray()
    await within(region).findByText('Filed as # 10500')
    await within(region).findByText('That file is too large.')
    await expectNoAxeViolations(region)
  })

  it('counts what was filed and what was refused apart', async () => {
    const copy = document({ docNumber: 10455, name: 'Re.eml', folder: inFolder })
    const { all } = routes({}, { refuse: (name) => (name.endsWith('.eml') ? new ApiError(409, 'already-filed', 'x', { code: 'already-filed', document: copy }) : undefined) })
    await renderApp('/folders/7', all)
    fireEvent.drop(await grid(), { dataTransfer: data([file('A.pdf'), file('B.pdf'), file('Re.eml')]) })
    const region = await tray()
    await within(region).findByText('Filed as # 10501')
    expect((await within(region).findByRole('status')).textContent).toBe('2 Filed, 1 Refused')
  })

  it('names the property a refusal is about', async () => {
    const { all } = routes({}, {
      refuse: () => new ApiError(422, 'validation', 'x', { code: 'validation', fields: [{ field: 'docclass', code: 'required', detail: 'x' }] }),
    })
    await renderApp('/folders/7', all)
    fireEvent.drop(await grid(), { dataTransfer: data([file('A.pdf')]) })
    expect(await within(await tray()).findByText('Class: This needs a value.')).toBeTruthy()
  })

  it('keeps an upload that is still running when the person moves to another view', async () => {
    let finish: (value: unknown) => void = () => {}
    const { all } = routes({ 'POST /v1/documents': () => new Promise((resolve) => (finish = resolve)) })
    const { router } = await renderApp('/folders/7', all)
    fireEvent.drop(await grid(), { dataTransfer: data([file('Slow.docx')]) })
    await within(await tray()).findByText('Uploading…')
    await router.navigate({ to: '/recent/$kind', params: { kind: 'documents' } })
    expect(within(await tray()).getByText('Uploading…')).toBeTruthy()
    finish(document({ docNumber: 10500, name: 'Slow.docx', folder: inFolder }))
    await within(await tray()).findByText('Filed as # 10500')
  })

  it('clears what is finished and keeps what runs, and folds away to its summary', async () => {
    let finish: (value: unknown) => void = () => {}
    let n = 0
    const { all } = routes({
      'POST /v1/documents': ({ body }: { body?: unknown }) => (n++ === 0 ? sentOf(body).then(() => document({ docNumber: 10500, name: 'Done.pdf', folder: inFolder })) : new Promise((resolve) => (finish = resolve))),
    })
    await renderApp('/folders/7', all)
    fireEvent.drop(await grid(), { dataTransfer: data([file('Done.pdf')]) })
    await within(await tray()).findByText('Filed as # 10500')
    fireEvent.drop(await grid(), { dataTransfer: data([file('Slow.pdf')]) })
    await within(await tray()).findByText('Uploading…')
    await userEvent.click(screen.getByRole('button', { name: 'Clear Finished' }))
    expect(screen.queryByText('Done.pdf')).toBeNull()
    expect(screen.getByText('Slow.pdf')).toBeTruthy()
    await userEvent.click(screen.getByRole('button', { name: 'Collapse' }))
    expect(screen.queryByText('Slow.pdf')).toBeNull()
    expect(screen.getByRole('button', { name: 'Expand' }).getAttribute('aria-expanded')).toBe('false')
    finish(document({ docNumber: 10501, name: 'Slow.pdf', folder: inFolder }))
  })
})

