// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { QueryClient } from '@tanstack/react-query'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect } from 'vitest'
import type { components } from '@healdan/api'
import { correspondence, filledForm, inFolder, letter, store, workspace } from '../filing/testing'
import { keys } from '../queries'
import { children, client, document, folder, matter, messageView, problem, trashItem, type Routes } from '../test/fakeApi'

type S = components['schemas']

export { correspondence, letter, store, workspace }

export const lease = document({ docNumber: 10432, name: 'Lease.docx', folder: inFolder })
export const plan = document({ docNumber: 10433, name: 'Plan.pdf', folder: inFolder })
export const drafts = folder({ id: 8, name: 'Drafts', path: [store, workspace, { id: 7, name: 'Correspondence', kind: 'folder' }, { id: 8, name: 'Drafts', kind: 'folder' }] })
export const matterTop = folder({ id: 110, name: '1001.1 – Charter Dispute', kind: 'matter', path: [store, workspace], canFile: false, canRename: false, canMove: false, canDelete: false })
/** A folder at the top of matter 1001.1, beside Correspondence (7) */
export const pleadings = folder({ id: 113, name: 'Pleadings', path: [store, workspace, { id: 113, name: 'Pleadings', kind: 'folder' }] })

/** Another client's matter, 1003.2, with one folder: a folder moved there leaves its matter (folders and Trash spec 7.5) */
export const licence = matter({ id: 13, number: 2, display: '1003.2 – Licence Review', name: 'Licence Review', client: client({ id: 3, number: 1003, name: 'Kestrel Licensing' }), folderId: 150 })
const licenceRef = { id: 13, display: '1003.2 – Licence Review', client: licence.client }
const licenceStep = { id: 150, name: '1003.2 – Licence Review', kind: 'matter' as const }
export const licenceTop = folder({ id: 150, name: '1003.2 – Licence Review', kind: 'matter', matter: licenceRef, path: [store, licenceStep], canFile: false, canRename: false, canMove: false, canDelete: false })
export const contracts = folder({ id: 151, name: 'Contracts', matter: licenceRef, path: [store, licenceStep, { id: 151, name: 'Contracts', kind: 'folder' }] })

/**
 * Puts a filed email's message in the cache, as Quick Look read it, with its first attachment shown as Saved as # `copy`,
 * and gives back its key.
 */
export function cacheMessage(queryClient: QueryClient, email: number, copy: number) {
  const key = keys.message(email, '1.0')
  const message = messageView()
  queryClient.setQueryData(key, { ...message, attachments: message.attachments.map((one, i) => (i === 0 ? { ...one, docNumber: copy } : one)) })
  return key
}

export interface Written {
  renamed: { docNumber: number; name: string }[]
  moved: { docNumber: number; folderId: number }[]
  deleted: number[]
  made: { parentId: number; name: string }[]
  renamedFolders: { id: number; name: string }[]
  deletedFolders: number[]
  /** Each folder move asked for: the folder, and the folder it was to go into */
  movedFolders: { id: number; folderId: number }[]
  /** Each restore asked for: the Trash item's id, and the body it was sent with */
  restored: { id: number; body: unknown }[]
}

/** What Drafts holds that the person can read, as `GET /v1/folders/8/contents` answers */
export const draftsContents: S['TrashContents'] = { folders: 3, documents: 11 }

/**
 * Routes for folder 7 with three documents and a folder that rename, move, delete and restore as the server would, and
 * record what was asked; `over` answers first, so a test can refuse a call. Each delete answers a Trash item of its own,
 * from 700 up, which a restore brings back.
 */
export function routes(over: Routes = {}) {
  let docs: S['DocumentView'][] = [letter, lease, plan]
  let subfolders: S['FolderView'][] = [drafts]
  const written: Written = { renamed: [], moved: [], deleted: [], made: [], renamedFolders: [], deletedFolders: [], movedFolders: [], restored: [] }
  // What each Trash item took, so its restore puts it back
  const trash = new Map<number, { item: S['TrashItem']; document?: S['DocumentView']; folder?: S['FolderView'] }>()
  let nextItem = 700
  const all: Routes = {
    'GET /v1/folders/7': correspondence,
    'GET /v1/folders/7/children': () => children([...subfolders, ...docs]),
    'GET /v1/folders/*/form': filledForm,
    'GET /v1/folders/8': drafts,
    'GET /v1/folders/8/children': children([]),
    'GET /v1/folders/110': matterTop,
    'GET /v1/folders/111': folder({ id: 111, name: 'Correspondence', path: [store, workspace, { id: 111, name: 'Correspondence', kind: 'folder' }] }),
    'GET /v1/folders/111/children': children([]),
    'PATCH /v1/folders/*': ({ params, body }: { params: string[]; body?: unknown }) => {
      const id = Number(params[0])
      const name = (body as { name: string }).name
      written.renamedFolders.push({ id, name })
      subfolders = subfolders.map((one) => (one.id === id ? { ...one, name } : one))
      return subfolders.find((one) => one.id === id) ?? folder({ id, name })
    },
    'PATCH /v1/documents/*': ({ params, body }: { params: string[]; body?: unknown }) => {
      const docNumber = Number(params[0])
      const name = (body as { name: string }).name
      written.renamed.push({ docNumber, name })
      docs = docs.map((one) => (one.docNumber === docNumber ? { ...one, name } : one))
      return docs.find((one) => one.docNumber === docNumber)
    },
    'POST /v1/documents/*/move': ({ params, body }: { params: string[]; body?: unknown }) => {
      const docNumber = Number(params[0])
      written.moved.push({ docNumber, folderId: (body as { folderId: number }).folderId })
      const moved = docs.find((one) => one.docNumber === docNumber)
      docs = docs.filter((one) => one.docNumber !== docNumber)
      return moved
    },
    'DELETE /v1/documents/*': ({ params }: { params: string[] }) => {
      const docNumber = Number(params[0])
      written.deleted.push(docNumber)
      const gone = docs.find((one) => one.docNumber === docNumber)
      docs = docs.filter((one) => one.docNumber !== docNumber)
      const item = trashItem({ id: nextItem++, docNumber, name: gone?.name ?? '' })
      trash.set(item.id, { item, document: gone })
      return item
    },
    'GET /v1/folders/*/contents': draftsContents,
    // The folder leaves the list it was in, and answers itself, as the server does
    'POST /v1/folders/*/move': ({ params, body }: { params: string[]; body?: unknown }) => {
      const id = Number(params[0])
      const folderId = (body as { folderId: number }).folderId
      written.movedFolders.push({ id, folderId })
      const moved = subfolders.find((one) => one.id === id) ?? folder({ id })
      subfolders = subfolders.filter((one) => one.id !== id)
      return moved
    },
    'DELETE /v1/folders/*': ({ params }: { params: string[] }) => {
      const id = Number(params[0])
      written.deletedFolders.push(id)
      const gone = subfolders.find((one) => one.id === id)
      subfolders = subfolders.filter((one) => one.id !== id)
      const item = trashItem({ id: nextItem++, type: 'folder', name: gone?.name ?? '', docNumber: null, kind: 'folder', size: null, contents: draftsContents })
      trash.set(item.id, { item, folder: gone })
      return item
    },
    'POST /v1/trash/*/restore': ({ params, body }: { params: string[]; body?: unknown }) => {
      const id = Number(params[0])
      written.restored.push({ id, body })
      const taken = trash.get(id)
      // Restored already, or never the person's
      if (!taken) return problem(404, 'not-found')
      trash.delete(id)
      if (taken.document) docs = [...docs, taken.document]
      if (taken.folder) subfolders = [...subfolders, taken.folder]
      return { place: correspondence, item: taken.item }
    },
    'POST /v1/folders': ({ body }: { body?: unknown }) => {
      const sent = body as { parentId: number; name: string }
      written.made.push(sent)
      return folder({ id: 9, name: sent.name })
    },
    ...over,
  }
  return { all, written }
}

export const main = () => screen.getByRole('main')
export const grid = () => within(main()).findByRole('grid')
export const row = async (name: RegExp) => within(await grid()).findByRole('row', { name })
export const toolbar = () => screen.findByRole('toolbar', { name: 'Folder Actions' })

/** A DataTransfer for a drag whose data is kept: what a drag's source writes is what a drop reads */
export function transfer() {
  const stored = new Map<string, string>()
  return {
    get types() {
      return [...stored.keys()]
    },
    setData: (type: string, data: string) => void stored.set(type, data),
    getData: (type: string) => stored.get(type) ?? '',
    effectAllowed: 'uninitialized',
    dropEffect: 'none',
    files: [] as File[],
  }
}

/**
 * Closes the open menu with Esc, and waits for React Aria to give the focus back to what had it, which it does a frame
 * after the menu has gone. A control pressed sooner, such as More, whose trigger takes no focus, would have its own menu
 * closed by that late focus, as no person could click so soon.
 */
export async function closeMenu() {
  await userEvent.keyboard('{Escape}')
  await waitFor(() => expect(screen.queryByRole('menu')).toBeNull())
  await waitFor(() => expect(globalThis.document.activeElement).not.toBe(globalThis.document.body))
}

/** Adds a row to the selection with Cmd (Ctrl in jsdom, which isn't a Mac): a plain click on a folder would open it */
export async function addRow(name: RegExp) {
  const user = userEvent.setup()
  await user.keyboard('{Control>}')
  await user.click(within(await row(name)).getByRole('rowheader'))
  await user.keyboard('{/Control}')
}

/** Selects a document's row by clicking its title, and adds more with Shift: from the first to each, the rows between */
export async function select(first: RegExp, ...more: RegExp[]) {
  const user = userEvent.setup()
  await user.click(within(await row(first)).getByRole('rowheader'))
  for (const name of more) {
    const target = await row(name)
    await user.keyboard('{Shift>}')
    await user.click(within(target).getByRole('rowheader'))
    await user.keyboard('{/Shift}')
  }
}
