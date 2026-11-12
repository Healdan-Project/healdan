// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { components } from '@healdan/api'
import { correspondence, filledForm, inFolder, letter, store, workspace } from '../filing/testing'
import { children, document, folder, type Routes } from '../test/fakeApi'

type S = components['schemas']

export { correspondence, letter, store, workspace }

export const lease = document({ docNumber: 10432, name: 'Lease.docx', folder: inFolder })
export const plan = document({ docNumber: 10433, name: 'Plan.pdf', folder: inFolder })
export const drafts = folder({ id: 8, name: 'Drafts', path: [store, workspace, { id: 7, name: 'Correspondence', kind: 'folder' }, { id: 8, name: 'Drafts', kind: 'folder' }] })
export const matterTop = folder({ id: 110, name: '1001.1 – Charter Dispute', kind: 'matter', path: [store, workspace], canFile: false })

export interface Written {
  renamed: { docNumber: number; name: string }[]
  moved: { docNumber: number; folderId: number }[]
  deleted: number[]
  made: { parentId: number; name: string }[]
}

/**
 * Routes for folder 7 with three documents that rename, move and delete as the server would, and record what was asked; `over`
 * answers first, so a test can refuse a call.
 */
export function routes(over: Routes = {}) {
  let docs: S['DocumentView'][] = [letter, lease, plan]
  const written: Written = { renamed: [], moved: [], deleted: [], made: [] }
  const all: Routes = {
    'GET /v1/folders/7': correspondence,
    'GET /v1/folders/7/children': () => children([drafts, ...docs]),
    'GET /v1/folders/*/form': filledForm,
    'GET /v1/folders/8': drafts,
    'GET /v1/folders/8/children': children([]),
    'GET /v1/folders/110': matterTop,
    'GET /v1/folders/111': folder({ id: 111, name: 'Correspondence', path: [store, workspace, { id: 111, name: 'Correspondence', kind: 'folder' }] }),
    'GET /v1/folders/111/children': children([]),
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
      docs = docs.filter((one) => one.docNumber !== docNumber)
      return undefined
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
