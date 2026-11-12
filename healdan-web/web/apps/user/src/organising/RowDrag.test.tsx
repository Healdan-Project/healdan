// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ApiError } from '@healdan/api'
import { ROWS_TYPE } from '../filing/dropTargets'
import { children, document as doc } from '../test/fakeApi'
import { readRows } from './rows'
import { renderApp } from '../test/renderApp'
import { lease, plan, routes, row, select, transfer } from './testing'

describe('Dragging rows onto a folder of the tree', () => {
  const tree = () => screen.findByRole('treegrid', { name: 'My Matters' })

  async function open(over = {}) {
    const { all, written } = routes(over)
    const rendered = await renderApp('/folders/7', all)
    const box = await tree()
    fireEvent.click(within(box).getByRole('button', { name: /expand/i }))
    return { ...rendered, box, written }
  }
  const label = async (box: HTMLElement, name: RegExp) => within(await within(box).findByRole('row', { name })).getByText(name.source.replace(/\\/g, ''))

  it('carries the selected rows under the app\'s own type when one of them is dragged, and only the row when it is dragged alone', async () => {
    await open()
    await select(/Lease/, /Plan/)
    const data = transfer()
    fireEvent.dragStart(await row(/Lease/), { dataTransfer: data })
    expect(data.types).toContain(ROWS_TYPE)
    expect(readRows(data.getData(ROWS_TYPE))).toEqual([
      { docNumber: 10432, name: 'Lease.docx', folder: { id: 7 } },
      { docNumber: 10433, name: 'Plan.pdf', folder: { id: 7 } },
    ])

    const other = transfer()
    fireEvent.dragStart(await row(/Letter to Dunmere/), { dataTransfer: other })
    expect(readRows(other.getData(ROWS_TYPE))).toEqual([{ docNumber: 10431, name: 'Letter to Dunmere Pryce.docx', folder: { id: 7 } }])
  })

  it('moves what was dragged into the folder it is dropped on', async () => {
    const { box, written } = await open()
    await select(/Lease/, /Plan/)
    const data = transfer()
    fireEvent.dragStart(await row(/Lease/), { dataTransfer: data })
    fireEvent.drop(await label(box, /Correspondence/), { dataTransfer: data })

    await waitFor(() =>
      expect(written.moved).toEqual([
        { docNumber: 10432, folderId: 111 },
        { docNumber: 10433, folderId: 111 },
      ]),
    )
    expect(await screen.findByText('Moved 2 documents to Correspondence')).toBeTruthy()
  })

  it('lights the folder up while rows are over it, and not for what it can\'t take', async () => {
    const { box } = await open()
    const target = await label(box, /Correspondence/)
    fireEvent.dragEnter(target, { dataTransfer: { types: ['text/html'] } })
    fireEvent.dragOver(target, { dataTransfer: { types: ['text/html'] } })
    expect(box.querySelector('[data-drop-target]')).toBeNull()
    fireEvent.dragEnter(target, { dataTransfer: { types: [ROWS_TYPE] } })
    expect(fireEvent.dragOver(target, { dataTransfer: { types: [ROWS_TYPE] } })).toBe(false)
    expect(box.querySelector('[data-drop-target="true"]')).toBeTruthy()
    fireEvent.dragLeave(target, { relatedTarget: globalThis.document.body })
    expect(box.querySelector('[data-drop-target]')).toBeNull()
  })

  it('refuses a matter\'s top level and a search folder with the filing rule\'s words, before any call', async () => {
    const { box, written, api } = await open()
    await select(/Lease/)
    const data = transfer()
    fireEvent.dragStart(await row(/Lease/), { dataTransfer: data })
    fireEvent.drop(within(within(box).getByRole('row', { name: /1001\.1/ })).getByText(/1001\.1/), { dataTransfer: data })
    expect(await screen.findByText('Choose a folder inside the matter')).toBeTruthy()
    fireEvent.drop(await label(box, /My Recent Docs Here/), { dataTransfer: data })
    expect(written.moved).toEqual([])
    expect(api.callsTo('POST /v1/documents/*/move')).toHaveLength(0)
  })

  it('names what the server refused, in the catalogue\'s words for a 403 and for a 404', async () => {
    const { box, written } = await open({
      'POST /v1/documents/10432/move': new ApiError(404, 'not-found', 'A server sentence that must not be shown raw.', { code: 'not-found' }),
      'POST /v1/documents/10433/move': new ApiError(403, 'forbidden', 'A server sentence that must not be shown raw.', { code: 'forbidden' }),
    })
    await select(/Lease/, /Plan/)
    const data = transfer()
    fireEvent.dragStart(await row(/Lease/), { dataTransfer: data })
    fireEvent.drop(await label(box, /Correspondence/), { dataTransfer: data })

    const dialog = await screen.findByRole('dialog', { name: "Some Documents Weren't Moved" })
    expect(within(dialog).getAllByRole('listitem').map((item) => item.textContent)).toEqual([
      "Lease.docx: Not found, or you can't see it.",
      "Plan.pdf: You don't have permission to do that.",
    ])
    expect(written.moved).toEqual([])
  })

  it('moves nothing from a drag that began in another page, whatever type it carries', async () => {
    const { box, written, api } = await open()
    const foreign = transfer()
    foreign.setData(ROWS_TYPE, JSON.stringify({ token: 'another page', documents: [{ docNumber: 10432, name: 'Lease.docx', folder: { id: 7 } }] }))
    const old = transfer()
    old.setData(ROWS_TYPE, JSON.stringify([{ docNumber: 10432, name: 'Lease.docx' }]))
    fireEvent.drop(await label(box, /Correspondence/), { dataTransfer: foreign })
    fireEvent.drop(await label(box, /Correspondence/), { dataTransfer: old })
    await new Promise((resolve) => setTimeout(resolve, 50))
    expect(written.moved).toEqual([])
    expect(api.callsTo('POST /v1/documents/*/move')).toHaveLength(0)
  })

  describe('onto the folder they are in', () => {
    const there = doc({ docNumber: 10440, name: 'There.docx', folder: { id: 111, name: 'Correspondence', kind: 'folder' } })

    it('makes no call and says so, rather than say the documents moved', async () => {
      const { box, written, api } = await open({ 'GET /v1/folders/7/children': () => children([there]) })
      await select(/There/)
      const data = transfer()
      fireEvent.dragStart(await row(/There/), { dataTransfer: data })
      fireEvent.drop(await label(box, /Correspondence/), { dataTransfer: data })
      expect(await screen.findByText("They're in that folder already.")).toBeTruthy()
      expect(written.moved).toEqual([])
      expect(api.callsTo('POST /v1/documents/*/move')).toHaveLength(0)
      expect(screen.queryByText(/^Moved/)).toBeNull()
    })

    it('moves the rest when only some are there, and counts only them', async () => {
      const { box, written } = await open({ 'GET /v1/folders/7/children': () => children([there, lease, plan]) })
      await select(/Lease/, /There/)
      const data = transfer()
      fireEvent.dragStart(await row(/Lease/), { dataTransfer: data })
      fireEvent.drop(await label(box, /Correspondence/), { dataTransfer: data })
      await waitFor(() => expect(written.moved).toEqual([{ docNumber: 10432, folderId: 111 }]))
      expect(await screen.findByText('Moved 1 document to Correspondence')).toBeTruthy()
    })
  })

  it('leaves a drag of files to the filing, which files them', async () => {
    const { box, written, api } = await open()
    fireEvent.drop(await label(box, /Correspondence/), { dataTransfer: { types: ['Files'], files: [new File(['x'], 'A.docx')] } })
    // The filing asks for the folder's form, as it does for a drop of files; no move is made
    await waitFor(() => expect(api.callsTo('GET /v1/folders/111/form')).toHaveLength(1))
    expect(written.moved).toEqual([])
  })
})

// A row for a document that isn't selectable would be a folder; drafts is one, and carries nothing
describe('Dragging a folder', () => {
  it('carries nothing', async () => {
    const { all } = routes()
    await renderApp('/folders/7', all)
    const data = transfer()
    fireEvent.dragStart(await row(/Drafts/), { dataTransfer: data })
    expect(data.types).not.toContain(ROWS_TYPE)
  })
})
