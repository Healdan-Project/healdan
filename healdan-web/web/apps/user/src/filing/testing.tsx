// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { createEvent, fireEvent, screen, within } from '@testing-library/react'
import { ApiError, type components } from '@healdan/api'
import { children, document, field, folder, page, type FakeApi, type Routes } from '../test/fakeApi'

type S = components['schemas']

export const store: S['FolderRef'] = { id: 2, name: 'Smoke', kind: 'store' }
export const workspace: S['FolderRef'] = { id: 110, name: '1001.1 – Charter Dispute', kind: 'matter' }
export const correspondence = folder({ id: 7, name: 'Correspondence', path: [store, workspace, { id: 7, name: 'Correspondence', kind: 'folder' }] })
export const inFolder = { id: 7, name: 'Correspondence', kind: 'folder' as const }
export const letter = document({ docNumber: 10431, name: 'Letter to Dunmere Pryce.docx', version: '3.1', fileVersion: '3.1', folder: inFolder })
export const jamie = { id: 3, name: 'Jamie Clarke' }
export const heldByJamie = { kind: 'checkout' as const, by: jamie, since: '2026-11-03T10:00:00Z', device: null, heldOn: null }

export const classes = [
  { value: 'Letters', label: 'Letters', class: null },
  { value: 'Invoices', label: 'Invoices', class: null },
]
/** The folder's form: Class has a default, so a drop files at once */
export const filledForm = page([
  field({ name: 'client', label: 'Client', value: '1001' }),
  field({ name: 'docclass', label: 'Class', required: true, editable: true, value: 'Letters', source: 'inherited', options: classes }),
  field({ name: 'author', label: 'Author', type: 'user', value: { id: 5, name: 'Priya Nair' }, source: 'default' }),
])
/** The same with Class required and empty: the form has to come first */
export const emptyForm = page(filledForm.items.map((one) => (one.name === 'docclass' ? { ...one, value: null, source: null } : one)))

export const file = (name: string) => new File(['content of ' + name], name)
export const data = (files: File[], types: string[] = ['Files']) => ({ types, files })

/** A drop with Option held: jsdom has no DragEvent, so the key is put on the event by hand */
export function dropWithOption(target: Element, files: File[]) {
  const event = createEvent.drop(target, { dataTransfer: data(files) })
  Object.defineProperty(event, 'altKey', { value: true })
  fireEvent(target, event)
}

export interface Sent {
  folderId: number
  name: string
  properties: Record<string, unknown> | null
}

/** What a POST to /v1/documents carried */
export async function sentOf(body: unknown): Promise<Sent> {
  return JSON.parse(await ((body as FormData).get('document') as Blob).text()) as Sent
}

/** Routes for folder 7 that file what is posted and list it afterwards; `refuse` answers a name with a problem */
export function routes(over: Routes = {}, options: { form?: unknown; refuse?: (name: string) => ApiError | undefined } = {}) {
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

export const main = () => screen.getByRole('main')
export const grid = async () => within(main()).findByRole('grid')
export const tray = () => screen.findByRole('region', { name: 'Filing' })
export const posts = (api: FakeApi) => api.callsTo('POST /v1/documents')
