// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { createEvent, fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { ApiError, type components } from '@healdan/api'
import { expectNoAxeViolations } from '../test/axe'
import { detailsRoutes, propertiesOf } from '../test/details'
import { children, document, field, folder, page, problem, type FakeApi, type Routes } from '../test/fakeApi'
import { renderApp } from '../test/renderApp'
import { ROWS_TYPE } from './dropTargets'

type S = components['schemas']

const store: S['FolderRef'] = { id: 2, name: 'Smoke', kind: 'store' }
const workspace: S['FolderRef'] = { id: 110, name: '1001.1 – Charter Dispute', kind: 'matter' }
const correspondence = folder({ id: 7, name: 'Correspondence', path: [store, workspace, { id: 7, name: 'Correspondence', kind: 'folder' }] })
const inFolder = { id: 7, name: 'Correspondence', kind: 'folder' as const }
const letter = document({ docNumber: 10431, name: 'Letter to Dunmere Pryce.docx', version: '3.1', fileVersion: '3.1', folder: inFolder })
const jamie = { id: 3, name: 'Jamie Clarke' }
const heldByJamie = { kind: 'checkout' as const, by: jamie, since: '2026-11-03T10:00:00Z', device: null }

const classes = [
  { value: 'Letters', label: 'Letters', class: null },
  { value: 'Invoices', label: 'Invoices', class: null },
]
/** The folder's form: Class has a default, so a drop files at once */
const filledForm = page([
  field({ name: 'client', label: 'Client', value: '1001' }),
  field({ name: 'docclass', label: 'Class', required: true, editable: true, value: 'Letters', source: 'inherited', options: classes }),
  field({ name: 'author', label: 'Author', type: 'user', value: { id: 5, name: 'Priya Nair' }, source: 'default' }),
])
/** The same with Class required and empty: the form has to come first */
const emptyForm = page(filledForm.items.map((one) => (one.name === 'docclass' ? { ...one, value: null, source: null } : one)))

const file = (name: string) => new File(['content of ' + name], name)
const data = (files: File[], types: string[] = ['Files']) => ({ types, files })

/** A drop with Option held: jsdom has no DragEvent, so the key is put on the event by hand */
function dropWithOption(target: Element, files: File[]) {
  const event = createEvent.drop(target, { dataTransfer: data(files) })
  Object.defineProperty(event, 'altKey', { value: true })
  fireEvent(target, event)
}

interface Sent {
  folderId: number
  name: string
  properties: Record<string, unknown> | null
}

/** What a POST to /v1/documents carried */
async function sentOf(body: unknown): Promise<Sent> {
  return JSON.parse(await ((body as FormData).get('document') as Blob).text()) as Sent
}

/** Routes for folder 7 that file what is posted and list it afterwards; `refuse` answers a name with a problem */
function routes(over: Routes = {}, options: { form?: unknown; refuse?: (name: string) => ApiError | undefined } = {}) {
  const filed: S['DocumentView'][] = []
  const sent: Sent[] = []
  let next = 10500
  const all: Routes = {
    'GET /v1/folders/7': correspondence,
    'GET /v1/folders/7/children': () => children([letter, ...filed]),
    'GET /v1/folders/*/form': options.form ?? filledForm,
    'POST /v1/documents': async ({ body }: { body?: unknown }) => {
      const one = await sentOf(body)
      sent.push(one)
      const refusal = options.refuse?.(one.name)
      if (refusal) throw refusal
      const made = document({ docNumber: next++, name: one.name, folder: { id: one.folderId, name: 'Correspondence', kind: 'folder' } })
      filed.push(made)
      return made
    },
    ...over,
  }
  return { all, filed, sent }
}

const main = () => screen.getByRole('main')
const grid = async () => within(main()).findByRole('grid')
const tray = () => screen.findByRole('region', { name: 'Filing' })
const posts = (api: FakeApi) => api.callsTo('POST /v1/documents')

describe('Filing by dropping files on a folder', () => {
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
    await waitFor(() => expect(router.history.location.pathname).toMatch(/^\/app\/(doc\/10455|folders\/7)/))
    expect(router.history.location.href).toMatch(/10455/)
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
    await within(await tray()).findByText("Your administrator doesn't allow that kind of file.")
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
})
