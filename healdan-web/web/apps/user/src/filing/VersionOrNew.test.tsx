// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { fireEvent, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { ApiError } from '@healdan/api'
import { children, document } from '../test/fakeApi'
import { renderApp } from '../test/renderApp'
import * as t from './testing'
const { letter, heldByJamie, file, data, dropWithOption, routes, grid, tray, posts } = t

describe('Dropping one file on a document\'s row', () => {
  const row = async () => within(await grid()).findByRole('row', { name: /Letter to Dunmere/ })
  const prompt = () => screen.findByRole('dialog', { name: 'Save as Version 4 of # 10431, or File as a New Document?' })

  it('asks, and saves it as the next version through POST /versions when that is chosen', async () => {
    let sent: FormData | undefined
    const { all } = routes({
      'POST /v1/documents/10431/versions': ({ body }: { body?: unknown }) => ((sent = body as FormData), document({ ...letter, version: '4.0', fileVersion: '4.0' })),
    })
    const { api } = await renderApp('/folders/7', all)
    fireEvent.drop(await row(), { dataTransfer: data([file('Draft 2.docx')]) })
    await userEvent.click(within(await prompt()).getByRole('button', { name: 'Save as Version 4' }))

    await within(await tray()).findByText('Saved as Version 4 of # 10431')
    expect(JSON.parse(await (sent!.get('version') as Blob).text())).toEqual({ kind: 'version', name: letter.name, ifHeld: false, ifVersion: '3.1' })
    expect((sent!.get('file') as File).name).toBe('Draft 2.docx')
    expect(posts(api)).toHaveLength(0)
  })

  it('files it as a new document instead when that is chosen', async () => {
    const { all, sent } = routes()
    const { api } = await renderApp('/folders/7', all)
    fireEvent.drop(await row(), { dataTransfer: data([file('Draft 2.docx')]) })
    await userEvent.click(within(await prompt()).getByRole('button', { name: 'File as a New Document' }))
    await within(await tray()).findByText('Filed as # 10500')
    expect(sent[0]).toMatchObject({ folderId: 7, name: 'Draft 2.docx' })
    expect(api.callsTo('POST /v1/documents/10431/versions')).toHaveLength(0)
  })

  it('files nothing when the question is cancelled', async () => {
    const { all, sent } = routes()
    const { api } = await renderApp('/folders/7', all)
    fireEvent.drop(await row(), { dataTransfer: data([file('Draft 2.docx')]) })
    await userEvent.click(within(await prompt()).getByRole('button', { name: 'Cancel' }))
    expect(sent).toHaveLength(0)
    expect(api.callsTo('POST /v1/documents/10431/versions')).toHaveLength(0)
  })

  it('says who holds a document held by someone else, and offers no version, only a new document', async () => {
    const held = document({ ...letter, hold: heldByJamie })
    const { all } = routes({ 'GET /v1/folders/7/children': children([held]) })
    const { api } = await renderApp('/folders/7', all)
    fireEvent.drop(await row(), { dataTransfer: data([file('Draft 2.docx')]) })
    const dialog = await prompt()
    expect(within(dialog).getByText(/Checked out by Jamie Clarke since/)).toBeTruthy()
    expect(within(dialog).getByText(/released on the computer it's checked out on, or in the Classic Interface/)).toBeTruthy()
    expect((within(dialog).getByRole('button', { name: 'Save as Version 4' }) as HTMLButtonElement).disabled).toBe(true)
    expect(within(dialog).getByRole('button', { name: 'File as a New Document' })).toBeTruthy()
    expect(api.calls.filter((call) => call.method === 'POST')).toHaveLength(0)
  })

  it('shows who holds it when the server refuses the save because a check-out began meanwhile', async () => {
    const { all } = routes({
      'POST /v1/documents/10431/versions': new ApiError(409, 'checked-out', 'x', { code: 'checked-out', hold: heldByJamie }),
    })
    await renderApp('/folders/7', all)
    fireEvent.drop(await row(), { dataTransfer: data([file('Draft 2.docx')]) })
    await userEvent.click(within(await prompt()).getByRole('button', { name: 'Save as Version 4' }))
    const region = await tray()
    expect(await within(region).findByText(/It's checked out\. Checked out by Jamie Clarke since/)).toBeTruthy()
  })

  it('saves into the person\'s own web check-out as its check-in', async () => {
    const mine = document({ ...letter, hold: { kind: 'checkout', by: { id: 5, name: 'Priya Nair' }, since: '2026-11-03T10:00:00Z', device: null } })
    let sent: FormData | undefined
    const { all } = routes({
      'GET /v1/folders/7/children': children([mine]),
      'POST /v1/documents/10431/versions': ({ body }: { body?: unknown }) => ((sent = body as FormData), letter),
    })
    await renderApp('/folders/7', all)
    fireEvent.drop(await row(), { dataTransfer: data([file('Draft 2.docx')]) })
    await userEvent.click(within(await prompt()).getByRole('button', { name: 'Save as Version 4' }))
    await within(await tray()).findByText(/Saved as Version/)
    expect(JSON.parse(await (sent!.get('version') as Blob).text())).toMatchObject({ kind: 'version', ifHeld: true })
  })

  it('files a file of another kind as a new document in the list\'s folder, without asking', async () => {
    const { all, sent } = routes()
    await renderApp('/folders/7', all)
    fireEvent.drop(await row(), { dataTransfer: data([file('Scan.pdf')]) })
    await within(await tray()).findByText('Filed as # 10500')
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(sent[0]).toMatchObject({ folderId: 7, name: 'Scan.pdf' })
  })

  it('asks when the extension is the document\'s in another case', async () => {
    const { all } = routes()
    await renderApp('/folders/7', all)
    fireEvent.drop(await row(), { dataTransfer: data([file('letter.DOCX')]) })
    expect(await prompt()).toBeTruthy()
  })

  it('files a new document, not a version, when Enter is pressed in the question', async () => {
    const { all, sent } = routes()
    const { api } = await renderApp('/folders/7', all)
    fireEvent.drop(await row(), { dataTransfer: data([file('Draft 2.docx')]) })
    await prompt()
    await userEvent.keyboard('{Enter}')
    await within(await tray()).findByText('Filed as # 10500')
    expect(sent).toHaveLength(1)
    expect(api.callsTo('POST /v1/documents/10431/versions')).toHaveLength(0)
  })

  it('keeps Option through "File as a New Document", so the form comes', async () => {
    const { all, sent } = routes()
    await renderApp('/folders/7', all)
    dropWithOption(await row(), [file('Draft 2.docx')])
    await userEvent.click(within(await prompt()).getByRole('button', { name: 'File as a New Document' }))
    expect(await screen.findByRole('dialog', { name: 'File Documents' })).toBeTruthy()
    expect(sent).toHaveLength(0)
  })
})

