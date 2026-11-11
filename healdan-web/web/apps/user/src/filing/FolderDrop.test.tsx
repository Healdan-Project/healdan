// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { detailsRoutes } from '../test/details'
import { children, folder, problem } from '../test/fakeApi'
import { renderApp } from '../test/renderApp'
import { ROWS_TYPE } from './dropTargets'
import * as t from './testing'
const { store, workspace, correspondence, letter, file, data, routes, main, grid, tray, posts } = t

describe('Dropping files on a folder', () => {
  it('files three files at once with the form\'s defaults, and the tray shows each with its #', async () => {
    const { all, sent } = routes()
    const { api } = await renderApp('/folders/7', all)
    fireEvent.drop(await grid(), { dataTransfer: data([file('Lease.docx'), file('Plan.pdf'), file('Note.txt')]) })

    const region = await tray()
    await within(region).findByText('Filed as # 10502')
    expect(within(region).getAllByRole('listitem').map((row) => row.textContent)).toEqual([
      expect.stringContaining('Lease.docx'),
      expect.stringContaining('Plan.pdf'),
      expect.stringContaining('Note.txt'),
    ])
    expect(screen.queryByRole('dialog')).toBeNull()
    // No properties of its own: the server fills in the folder's Class, and the caller as Author and Typist
    expect(sent).toEqual([
      { folderId: 7, name: 'Lease.docx', properties: null, checkOut: false },
      { folderId: 7, name: 'Plan.pdf', properties: null, checkOut: false },
      { folderId: 7, name: 'Note.txt', properties: null, checkOut: false },
    ])
    expect(posts(api)).toHaveLength(3)
  })


  it('shows the new rows in the list, once, with a flash', async () => {
    const { all } = routes()
    await renderApp('/folders/7', all)
    fireEvent.drop(await grid(), { dataTransfer: data([file('Lease.docx')]) })
    const row = await within(await grid()).findByRole('row', { name: /Lease\.docx/ })
    expect(row.getAttribute('data-flash')).toBe('true')
    // The flash is a style that stops for a person who asks for less motion
    const css = (await import('@healdan/ui/src/components/Table.module.css?raw')).default
    expect(css).toMatch(/prefers-reduced-motion: reduce\) \{\s*\.row\[data-flash\] \{\s*animation: none/)
  })


  it('files one folder\'s drop once however many files and events: a drop on a row inside the list isn\'t also the list\'s', async () => {
    const { all } = routes()
    const { api } = await renderApp('/folders/7', all)
    const row = await within(await grid()).findByRole('row', { name: /Letter to Dunmere/ })
    // A drop of two files on a row is no version: it files both as new documents, once each
    fireEvent.drop(row, { dataTransfer: data([file('A.pdf'), file('B.pdf')]) })
    await within(await tray()).findByText('Filed as # 10501')
    expect(posts(api)).toHaveLength(2)
  })


  it('files nothing on a matter\'s top level, and says to choose a folder inside it', async () => {
    const top = folder({ id: 110, name: '1001.1 – Charter Dispute', kind: 'matter', path: [store, workspace], canFile: false })
    const { all, sent } = routes({ 'GET /v1/folders/110': top, 'GET /v1/folders/110/children': children([]) })
    const { api } = await renderApp('/folders/110', all)
    fireEvent.drop(await grid(), { dataTransfer: data([file('Lease.docx')]) })
    expect(await screen.findByText('Choose a folder inside the matter')).toBeTruthy()
    expect(sent).toHaveLength(0)
    expect(api.callsTo('GET /v1/folders/110/form')).toHaveLength(0)
    expect(screen.queryByRole('region', { name: 'Filing' })).toBeNull()
  })


  it('files nothing in a search folder either', async () => {
    const search = folder({ id: 112, name: 'My Recent Docs Here', kind: 'searchFolder', path: [store, workspace, { id: 112, name: 'My Recent Docs Here', kind: 'searchFolder' }], canFile: false })
    const { all, sent } = routes({ 'GET /v1/folders/112': search, 'GET /v1/folders/112/children': children([]) })
    await renderApp('/folders/112', all)
    fireEvent.drop(await grid(), { dataTransfer: data([file('Lease.docx')]) })
    expect(await screen.findByText('Choose a folder inside the matter')).toBeTruthy()
    expect(sent).toHaveLength(0)
  })


  it('says a folder the person may not write to is no place to file, without asking the server', async () => {
    const readOnly = folder({ ...correspondence, canFile: false })
    const { all, sent } = routes({ 'GET /v1/folders/7': readOnly })
    await renderApp('/folders/7', all)
    fireEvent.drop(await grid(), { dataTransfer: data([file('Lease.docx')]) })
    expect(await screen.findByText("You can't file into this folder.")).toBeTruthy()
    expect(sent).toHaveLength(0)
  })


  it('shows the server\'s refusal of the form (a filing rule) from the catalogue', async () => {
    const { all, sent } = routes({ 'GET /v1/folders/7/form': problem(409, 'filing-rule') })
    await renderApp('/folders/7', all)
    fireEvent.drop(await grid(), { dataTransfer: data([file('Lease.docx')]) })
    expect(await screen.findByText("That can't be filed there.")).toBeTruthy()
    expect(sent).toHaveLength(0)
  })


  it('says to drag the email to Finder first when a drop carries no files, and ignores the app\'s own rows', async () => {
    const { all, sent } = routes()
    await renderApp('/folders/7', all)
    fireEvent.drop(await grid(), { dataTransfer: data([], ['text/uri-list', 'text/plain']) })
    expect(await screen.findByText("Drag the email to Finder first, or file it from Outlook's Healdan pane")).toBeTruthy()
    expect(sent).toHaveLength(0)
  })


  it('leaves a drop of the app\'s own rows alone, for the move to take', async () => {
    const { all, sent } = routes()
    await renderApp('/folders/7', all)
    const target = await grid()
    const taken = fireEvent.drop(target, { dataTransfer: data([], [ROWS_TYPE]) })
    expect(taken).toBe(true) // not cancelled: nobody here handled it
    expect(screen.queryByText(/Drag the email to Finder/)).toBeNull()
    expect(sent).toHaveLength(0)
  })


  it('takes a dragover of files or a link, so the browser drops it here and doesn\'t follow the link, and leaves the app\'s own rows', async () => {
    const { all } = routes()
    await renderApp('/folders/7', all)
    const target = await grid()
    expect(fireEvent.dragOver(target, { dataTransfer: data([], ['Files']) })).toBe(false)
    expect(fireEvent.dragOver(target, { dataTransfer: data([], ['text/uri-list', 'text/plain']) })).toBe(false)
    expect(fireEvent.dragOver(target, { dataTransfer: data([], [ROWS_TYPE, 'text/plain']) })).toBe(true)
    expect(fireEvent.dragOver(target, { dataTransfer: data([], ['text/html']) })).toBe(true)
  })
})

describe('Dropping on a folder in the tree', () => {
  const tree = () => screen.findByRole('treegrid', { name: 'My Matters' })

  async function open() {
    const { all, sent } = routes({ 'GET /v1/folders/111': folder({ id: 111, name: 'Correspondence' }), 'GET /v1/folders/111/children': children([]) })
    const rendered = await renderApp('/folders/7', all)
    const box = await tree()
    fireEvent.click(within(box).getByRole('button', { name: /expand/i }))
    return { ...rendered, box, sent }
  }
  const label = async (box: HTMLElement, name: RegExp) => within(await within(box).findByRole('row', { name })).getByText(name.source.replace(/\\/g, ''))

  it('files into that folder', async () => {
    const { box, sent } = await open()
    fireEvent.drop(await label(box, /Correspondence/), { dataTransfer: data([file('Lease.docx')]) })
    await within(await tray()).findByText(/Filed as # 10500/)
    expect(sent).toEqual([expect.objectContaining({ folderId: 111, name: 'Lease.docx' })])
  })

  it('lights up the folder while files are over it, and not for anything else', async () => {
    const { box } = await open()
    const target = await label(box, /Correspondence/)
    fireEvent.dragOver(target, { dataTransfer: data([], ['text/html']) })
    expect(box.querySelector('[data-drop-target]')).toBeNull()
    fireEvent.dragEnter(target, { dataTransfer: data([], ['Files']) })
    fireEvent.dragOver(target, { dataTransfer: data([], ['Files']) })
    expect(box.querySelector('[data-drop-target="true"]')).toBeTruthy()
    fireEvent.dragLeave(target, { relatedTarget: globalThis.document.body })
    expect(box.querySelector('[data-drop-target]')).toBeNull()
  })

  it('refuses a matter\'s top level and a search folder, as the list does', async () => {
    const { box, sent } = await open()
    fireEvent.drop(within(within(box).getByRole('row', { name: /1001\.1/ })).getByText(/1001\.1/), { dataTransfer: data([file('A.docx')]) })
    expect(await screen.findByText('Choose a folder inside the matter')).toBeTruthy()
    fireEvent.drop(await label(box, /My Recent Docs Here/), { dataTransfer: data([file('B.docx')]) })
    await waitFor(() => expect(screen.getAllByText('Choose a folder inside the matter').length).toBeGreaterThan(0))
    expect(sent).toHaveLength(0)
  })
})

describe('The Upload button', () => {
  it('picks files for the open folder and files them through the same queue', async () => {
    const { all, sent } = routes()
    await renderApp('/folders/7', all)
    const toolbar = await screen.findByRole('toolbar', { name: 'Folder Actions' })
    expect(within(toolbar).getByRole('button', { name: 'Upload' })).toBeTruthy()
    await userEvent.upload(screen.getByLabelText('Choose Files to Upload'), [file('Lease.docx'), file('Plan.pdf')])
    await within(await tray()).findByText('Filed as # 10501')
    expect(sent.map((one) => [one.folderId, one.name])).toEqual([[7, 'Lease.docx'], [7, 'Plan.pdf']])
  })

  it('says to choose a folder inside the matter, and opens no picker, on a matter\'s top level', async () => {
    const top = folder({ id: 110, name: '1001.1 – Charter Dispute', kind: 'matter', path: [store, workspace], canFile: false })
    const { all } = routes({ 'GET /v1/folders/110': top, 'GET /v1/folders/110/children': children([]) })
    await renderApp('/folders/110', all)
    const toolbar = await screen.findByRole('toolbar', { name: 'Folder Actions' })
    await userEvent.click(within(toolbar).getByRole('button', { name: 'Upload' }))
    expect(await screen.findByText('Choose a folder inside the matter')).toBeTruthy()
  })
})

describe('Dropping outside every target', () => {
  it('cancels a file dropped on the details, so the browser doesn\'t open it in place of the app, and files nothing', async () => {
    const { all, sent } = routes(detailsRoutes(letter))
    await renderApp('/folders/7?sel=10431', all)
    const details = await screen.findByRole('region', { name: 'Details' })
    expect(fireEvent.dragOver(details, { dataTransfer: data([], ['Files']) })).toBe(false)
    expect(fireEvent.drop(details, { dataTransfer: data([file('Lease.docx')]) })).toBe(false)
    // A link is cancelled too, or the page would follow it
    expect(fireEvent.drop(details, { dataTransfer: data([], ['text/uri-list']) })).toBe(false)
    expect(sent).toHaveLength(0)
  })

  it('leaves the app\'s own rows and anything typed into a field to the browser', async () => {
    const { all } = routes(detailsRoutes(letter))
    await renderApp('/folders/7?sel=10431', all)
    const details = await screen.findByRole('region', { name: 'Details' })
    expect(fireEvent.drop(details, { dataTransfer: data([], [ROWS_TYPE]) })).toBe(true)
    const box = globalThis.document.createElement('input')
    globalThis.document.body.append(box)
    expect(fireEvent.dragOver(box, { dataTransfer: data([], ['Files']) })).toBe(true)
    box.remove()
  })
})

describe('Dropping on a folder\'s row', () => {
  const drafts = folder({ id: 8, name: 'Drafts', path: [...correspondence.path, { id: 8, name: 'Drafts', kind: 'folder' }] })
  const rowOf = async (name: RegExp) => within(await grid()).findByRole('row', { name })

  it('files into that folder, and the tray names it', async () => {
    const { all, sent } = routes({ 'GET /v1/folders/7/children': children([drafts, letter]) })
    await renderApp('/folders/7', all)
    fireEvent.drop(await rowOf(/Drafts/), { dataTransfer: data([file('Lease.docx')]) })
    const region = await tray()
    await within(region).findByText('Filed as # 10500')
    expect(sent).toEqual([expect.objectContaining({ folderId: 8, name: 'Lease.docx' })])
    expect(within(region).getByText('In Drafts')).toBeTruthy()
  })

  it('files into Documents from the matter\'s top level, which refuses a drop on the list itself', async () => {
    const top = folder({ id: 110, name: '1001.1 – Charter Dispute', kind: 'matter', path: [store, workspace], canFile: false })
    const documents = folder({ id: 120, name: 'Documents', path: [store, workspace, { id: 120, name: 'Documents', kind: 'folder' }] })
    const { all, sent } = routes({ 'GET /v1/folders/110': top, 'GET /v1/folders/110/children': children([documents]) })
    await renderApp('/folders/110', all)
    fireEvent.drop(await grid(), { dataTransfer: data([file('A.docx')]) })
    expect(await screen.findByText('Choose a folder inside the matter')).toBeTruthy()
    expect(sent).toHaveLength(0)
    fireEvent.drop(await rowOf(/Documents/), { dataTransfer: data([file('B.docx')]) })
    await within(await tray()).findByText('Filed as # 10500')
    expect(sent).toEqual([expect.objectContaining({ folderId: 120, name: 'B.docx' })])
  })

  it('refuses a search folder\'s row', async () => {
    const search = folder({ id: 9, name: 'Recent Here', kind: 'searchFolder', canFile: false })
    const { all, sent } = routes({ 'GET /v1/folders/7/children': children([search]) })
    await renderApp('/folders/7', all)
    fireEvent.drop(await rowOf(/Recent Here/), { dataTransfer: data([file('A.docx')]) })
    expect(await screen.findByText('Choose a folder inside the matter')).toBeTruthy()
    expect(sent).toHaveLength(0)
  })

  it('lights the row, not the whole list, while files are over it', async () => {
    const { all } = routes({ 'GET /v1/folders/7/children': children([drafts, letter]) })
    await renderApp('/folders/7', all)
    const row = await rowOf(/Drafts/)
    fireEvent.dragEnter(row, { dataTransfer: data([], ['Files']) })
    fireEvent.dragOver(row, { dataTransfer: data([], ['Files']) })
    expect(row.getAttribute('data-dropping')).toBe('true')
    expect(main().querySelector('[data-dropping="true"]:not([role="row"])')).toBeNull()
  })
})

describe('The outline while files are dragged over the list', () => {
  it('stays while the drag moves between children, though dragleave names no element (as WebKit may), and goes at the end', async () => {
    const { all } = routes()
    await renderApp('/folders/7', all)
    const zone = (await grid()).closest('[data-dropping], section > div') as HTMLElement
    const inner = await within(await grid()).findByRole('row', { name: /Letter to Dunmere/ })
    const over = { dataTransfer: data([], ['Files']) }
    fireEvent.dragEnter(zone, over)
    fireEvent.dragOver(zone, over)
    fireEvent.dragEnter(inner, over)
    fireEvent.dragLeave(zone, { ...over, relatedTarget: null })
    fireEvent.dragOver(inner, over)
    expect(main().querySelector('[data-dropping]')).toBeTruthy()
    fireEvent.dragLeave(inner, { ...over, relatedTarget: null })
    expect(main().querySelector('[data-dropping="true"]:not([role="row"])')).toBeNull()
  })
})
