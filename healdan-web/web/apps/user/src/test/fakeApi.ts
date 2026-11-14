// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { ApiError, type Api, type components } from '@healdan/api'
import type { PageOf } from '../pages'

type S = components['schemas']

// Fixtures: each is a whole schema object, so a field the API adds shows up here as a type error.
export function client(over: Partial<S['ClientRef']> = {}): S['ClientRef'] {
  return { id: 1, number: 1001, name: 'Saltmarsh Shipping Ltd', ...over }
}

export function matter(over: Partial<S['MatterView']> = {}): S['MatterView'] {
  return {
    type: 'matter',
    id: 11,
    number: 1,
    display: '1001.1 – Charter Dispute',
    name: 'Charter Dispute',
    description: null,
    client: client(),
    store: { id: 2, name: 'Smoke' },
    folderId: 110,
    lastChange: null,
    ...over,
  }
}

export function folder(over: Partial<S['FolderView']> = {}): S['FolderView'] {
  return {
    type: 'folder',
    id: 111,
    name: 'Correspondence',
    kind: 'folder',
    matter: { id: 11, display: '1001.1 – Charter Dispute', client: client() },
    store: { id: 2, name: 'Smoke' },
    path: [],
    canFile: true,
    ...over,
  }
}

/** The kind the server makes of a file name, for the extensions the tests use */
export function kindOf(name: string): S['DocumentView']['kind'] {
  const kinds: Record<string, S['DocumentView']['kind']> = {
    pdf: 'pdf',
    doc: 'word',
    docx: 'word',
    xls: 'excel',
    xlsx: 'excel',
    ppt: 'powerpoint',
    pptx: 'powerpoint',
    eml: 'email',
    msg: 'email',
    odt: 'opendocument',
    png: 'picture',
    jpg: 'picture',
    tif: 'picture',
    heic: 'picture',
  }
  return kinds[name.slice(name.lastIndexOf('.') + 1).toLowerCase()] ?? 'other'
}

export function document(over: Partial<S['DocumentView']> = {}): S['DocumentView'] {
  const name = over.name ?? 'Letter from Dunmere Pryce.pdf'
  return {
    type: 'document',
    docNumber: 10388,
    name,
    kind: kindOf(name),
    size: 214_000,
    version: '1.0',
    fileVersion: '1.0',
    reference: '10388',
    folder: { id: 111, name: 'Correspondence', kind: 'folder' },
    matter: { id: 11, display: '1001.1 – Charter Dispute', client: client() },
    author: { username: 'jclarke', displayName: 'Jamie Clarke' },
    dateFiled: '2026-09-29T09:40:00Z',
    lastModified: '2026-09-29T09:40:00Z',
    hold: null,
    canEdit: true,
    canOpen: true,
    ...over,
  }
}

/** A filed email as GET /documents/{n}/message answers: a subject, a sender, two recipients, a PDF and a picture. */
export function messageView(over: Partial<S['MessageView']> = {}): S['MessageView'] {
  return {
    subject: 'Re: Charter Dispute',
    from: 'Dunmere Pryce <dunmere@pryce.example>',
    to: 'Priya Nair <priya@example.test>',
    cc: 'Jamie Clarke <jclarke@example.test>',
    sent: '2026-11-03T10:00:00Z',
    received: '2026-11-03T10:07:05Z',
    attachments: [
      { index: 1, name: 'Contract.pdf', type: 'application/pdf', size: 120_000, message: false, docNumber: null },
      { index: 2, name: 'Site Photo.png', type: 'image/png', size: 40_000, message: false, docNumber: null },
      { index: 3, name: 'Schedule.xlsx', type: 'application/vnd.ms-excel', size: 9_000, message: false, docNumber: null },
    ],
    body: '/api/v1/documents/10388/message/body',
    ...over,
  }
}

/** One property of a document, as GET /documents/{n}/properties lists it. */
export function field(over: Partial<S['ProfileField']> = {}): S['ProfileField'] {
  return { name: 'docclass', label: 'Class', type: 'string', required: false, editable: false, value: null, source: null, inheritedFrom: null, overrides: null, options: null, ...over }
}

/** One row of GET /documents/{n}/versions. */
export function versionRow(over: Partial<S['VersionView']> = {}): S['VersionView'] {
  return { version: '1.0', fileVersion: '1.0', kind: 'version', date: '2026-09-29T09:40:00Z', by: { id: 3, name: 'Jamie Clarke' }, comment: null, event: 'event.stored', ...over }
}

/** A favorite, with its item's own name unless it's given one of its own */
export function favorite(item: S['FolderView'] | S['DocumentView'], id = 900, name = item.name): S['FavoriteView'] {
  return { id, type: item.type, name, item }
}

export function page<T>(items: T[]): { items: T[]; offset: number; limit: number; total: number } {
  return { items, offset: 0, limit: items.length, total: items.length }
}

type Child = S['FolderView'] | S['DocumentView']

/** A folder's children as GET /folders/{id}/children answers: `documents` counts the documents among them. */
export function children(items: Child[], over: { offset?: number; limit?: number; total?: number; documents?: number } = {}) {
  const documents = items.filter((item) => item.type === 'document').length
  return { items, offset: 0, limit: 100, total: items.length, documents, ...over } satisfies PageOf<Child> & { documents: number }
}

export const me: S['UserView'] = {
  id: 5,
  username: 'priya',
  name: 'Priya Nair',
  email: 'priya@example.test',
  language: 'en',
  admin: false,
}

/** The browser's time zone, which every list, search and count sends for the Edit Date filters (R90) */
export const zone = Intl.DateTimeFormat().resolvedOptions().timeZone

export function problem(status: number, code: string, detail = 'A server sentence that must not be shown raw.'): ApiError {
  return new ApiError(status, code, detail, { status, code, detail })
}

export interface Call {
  method: string
  path: string
  query?: Record<string, unknown>
  /** The signal a get was given: a request nobody waits for any more has it aborted */
  signal?: AbortSignal
}

type Handler = (request: { params: string[]; query?: Record<string, unknown>; body?: unknown; signal?: AbortSignal }) => unknown

export type Routes = Record<string, unknown | Handler>

/** What a signed-in person with one matter sees. Routes read `METHOD /path`, where `*` matches one segment. */
export function defaultRoutes(): Routes {
  return {
    'GET /v1/me': me,
    'GET /v1/documents/recent': page([
      document({ docNumber: 10431, name: 'Letter to Dunmere Pryce.docx', lastModified: '2026-11-09T09:42:00Z' }),
      document({ docNumber: 10388, name: 'Letter from Dunmere Pryce.pdf', lastModified: '2026-09-29T09:40:00Z' }),
    ]),
    'GET /v1/matters/recent': page([
      matter({ id: 12, folderId: 120, display: '1002.1 – Commercial Lease', client: client({ id: 2, number: 1002, name: 'Brightwell' }) }),
    ]),
    'GET /v1/matters/mine': page([matter()]),
    'GET /v1/me/favorites': page([
      favorite(document({ docNumber: 10500, name: 'Cap Table.xlsx' }), 901),
      favorite(folder({ id: 130, name: 'Due Diligence' }), 902),
    ]),
    'GET /v1/suggest': { matters: [], documents: [] },
    'GET /v1/folders/recent': page([folder({ id: 111, name: 'Correspondence' })]),
    'GET /v1/clients/recent': [{ number: 1001, name: 'Saltmarsh Shipping Ltd' }],
    'GET /v1/folders/*/children': children([
      folder({ id: 111, name: 'Correspondence' }),
      folder({ id: 112, name: 'My Recent Docs Here', kind: 'searchFolder' }),
    ]),
    'DELETE /v1/session': undefined,
  }
}

export interface FakeApi extends Api {
  calls: Call[]
  /** The calls made to a path, as `METHOD /path` matches. */
  callsTo(pattern: string): Call[]
}

function matcher(pattern: string): RegExp {
  const escaped = pattern.replace(/[.+?^${}()|[\]\\]/g, '\\$&').replace(/\*/g, '([^/]+)')
  return new RegExp(`^${escaped}$`)
}

/**
 * An Api that answers from `routes` (over the defaults); a value that is an Error is thrown. A 401 calls onSignedOut
 * first, as createApi's does.
 */
export function fakeApi(over: Routes = {}, onSignedOut?: () => void): FakeApi {
  const routes = { ...defaultRoutes(), ...over }
  const calls: Call[] = []

  async function answer<T>(method: string, path: string, query?: Record<string, unknown>, body?: unknown, signal?: AbortSignal): Promise<T> {
    calls.push({ method, path, query, signal })
    // The newest override wins, so a test's route comes before a default one it also matches.
    for (const key of Object.keys(routes).reverse()) {
      const match = matcher(key).exec(`${method} ${path}`)
      if (!match) continue
      const value = routes[key]
      const result = typeof value === 'function' ? (value as Handler)({ params: match.slice(1), query, body, signal }) : value
      if (result instanceof ApiError && result.status === 401) onSignedOut?.()
      if (result instanceof Error) throw result
      return result as T
    }
    throw problem(404, 'not-found')
  }

  return {
    calls,
    callsTo: (pattern) => calls.filter((c) => matcher(pattern).test(`${c.method} ${c.path}`)),
    get: (path, query, options) => answer('GET', path, query, undefined, options?.signal),
    // A route for a binary answer gives a Response (or throws, as a failed request does)
    getResponse: (path, query, options) => answer<Response>('GET', path, query, undefined, options?.signal),
    send: (method, path, body) => answer(method, path, undefined, body),
    url: (path, query) => {
      const params = new URLSearchParams()
      for (const [name, value] of Object.entries(query ?? {})) if (value !== undefined) params.append(name, String(value))
      const text = params.toString()
      return `/api${path}${text ? `?${text}` : ''}`
    },
  }
}
