// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { ApiError, type adminComponents, type Api, type components } from '@healdan/api'

type S = components['schemas']
type A = adminComponents['schemas']

// Fixtures: each is a whole schema object, so a field the API adds shows up here as a type error. /v1/me is the shared
// contract's (openapi.yaml), and the admin bodies openapi-admin.yaml's. The names are the demo firm's, the numbers invented.

/** An administrator, as GET /v1/me answers for one */
export const me: S['UserView'] = {
  id: 1,
  username: 'admin',
  name: 'Alex Morgan',
  email: 'alex@example.test',
  language: 'en',
  admin: true,
}

const MB = 1024 ** 2
const GB = 1024 ** 3

/** A Server Health tile that was read, in good health, linking to the Classic Interface */
export function tile(name: A['HealthTile']['name'], over: Partial<A['HealthTile']> = {}): A['HealthTile'] {
  return { name, status: 'ok', detail: `The ${name} line`, used: null, total: null, link: '/frontend.jsp', problem: null, ...over }
}

/** The ten tiles, in the order the server sends them */
export const health: A['HealthTile'][] = [
  tile('server', { detail: 'Up 6 days, Healdan 9.3.2' }),
  tile('database', { detail: 'PostgreSQL 18.4, 1.1 GB', used: Math.round(1.1 * GB) }),
  tile('disk', { detail: '195 of 500 GB used', used: 195 * GB, total: 500 * GB }),
  tile('memory', { detail: '2.4 of 4 GB used', used: Math.round(2.4 * GB), total: 4 * GB }),
  tile('sign-in', { detail: 'Authentik answering' }),
  tile('storage-backends', { status: 'attention', detail: '2 reachable, 3 files stuck' }),
  tile('search-index', { status: 'attention', detail: '5 waiting, 2 failed', link: '/admin/search-index' }),
  tile('previews', { detail: '3 waiting, none given up' }),
  tile('background-jobs', { detail: '11 on schedule' }),
  tile('outgoing-email', { status: 'off', detail: 'Not set up' }),
]

export const attention: A['AttentionItem'][] = [
  { code: 'files-stuck', count: 3, detail: 'Client Work, moving to Wasabi EU', link: '/frontend.jsp' },
  { code: 'index-failed', count: 2, detail: '# 10412, # 10398', link: '/admin/search-index' },
  { code: 'errors', count: 14, detail: 'Latest 10:41, a preview timed out', link: '/frontend.jsp' },
]

/** GET /v1/admin/dashboard: three things to act on, and the ten tiles; every source checked */
export const dashboard: A['Dashboard'] = { attention, health, unchecked: [], readAt: '2026-11-19T10:41:00Z' }

// The last 30 days' filings, oldest first, today (2026-11-19) last: the weekends quieter. The last 7 add up to 91, and all
// 30 to 388, as the documents' totals say
const FILED = [9, 16, 13, 4, 2, 19, 15, 21, 12, 17, 3, 1, 20, 28, 18, 13, 15, 3, 2, 14, 19, 22, 11, 18, 2, 3, 24, 16, 21, 7]

/** GET /v1/admin/dashboard/usage, as last worked out at 10:41 on 19 November 2026 */
export const usage: A['Usage'] = {
  computedAt: '2026-11-19T10:41:00Z',
  failedAt: null,
  documents: { total: 1284, last7Days: 91, last30Days: 388 },
  storage: { total: Math.round(14.2 * GB), last7Days: 310 * MB, last30Days: Math.round(1.6 * GB) },
  matters: { total: 12, last7Days: 0, last30Days: 1 },
  clients: { total: 7, last7Days: 0, last30Days: 0 },
  people: { total: 6, last7Days: 0, last30Days: 1 },
  signedIn: 4,
  filedPerDay: FILED.map((count, i) => ({ date: new Date(Date.UTC(2026, 9, 21 + i)).toISOString().slice(0, 10), count })),
  stores: [
    { id: 4, name: 'Client Work', documents: 1004, bytes: Math.round(11.8 * GB), backend: { id: 2, name: 'Wasabi EU', kind: 's3', encrypted: true } },
    { id: 3, name: 'Firm', documents: 280, bytes: Math.round(2.4 * GB), backend: { id: 1, name: 'Original', kind: 'local', encrypted: false } },
  ],
  busiestMatters: [
    { id: 11, number: '1001.1', name: 'Charter Dispute', filed: 14 },
    { id: 31, number: '1003.1', name: 'Series A Investment', filed: 9 },
    { id: 22, number: '1002.2', name: 'Employment Tribunal', filed: 6 },
  ],
}

/** The usage before the server has worked it out once since it started */
export const usageNotYet: A['Usage'] = {
  computedAt: null,
  failedAt: null,
  documents: null,
  storage: null,
  matters: null,
  clients: null,
  people: null,
  signedIn: 1,
  filedPerDay: [],
  stores: [],
  busiestMatters: [],
}

export const saltmarsh: A['FoundClient'] = { id: 1, number: 1001, name: 'Saltmarsh Shipping Ltd' }

/** What GET /v1/admin/find finds for "sal" */
export const found: A['Found'] = {
  clients: [saltmarsh],
  matters: [{ id: 11, number: '1001.1', name: 'Charter Dispute', client: saltmarsh }],
  people: [
    { id: 7, username: 'priya', name: 'Priya Nair', enabled: true },
    { id: 8, username: 'sally.old', name: 'Sally Oldham', enabled: false },
  ],
}

export const nothingFound: A['Found'] = { clients: [], matters: [], people: [] }

// The firm's clients, Stores, people and templates, as the Matters list and its filters name them
export const ashcombe: A['ClientBrief'] = { id: 9, number: 998, name: 'Ashcombe Reeve LLP' }
export const brightwell: A['ClientBrief'] = { id: 2, number: 1002, name: 'Brightwell Bakeries Ltd' }
export const kestrel: A['ClientBrief'] = { id: 3, number: 1003, name: 'Kestrel Software Ltd' }

export const firmStore: A['Store'] = { id: 3, name: 'Firm' }
export const clientWork: A['Store'] = { id: 4, name: 'Client Work' }
/** GET /v1/admin/stores: by name */
export const stores: A['Store'][] = [clientWork, firmStore]

export const helen: A['PersonBrief'] = { id: 5, username: 'helen', name: 'Helen Ashcombe' }
export const tom: A['PersonBrief'] = { id: 6, username: 'tom', name: 'Tom Reeve' }
export const sam: A['PersonBrief'] = { id: 10, username: 'sam', name: 'Sam Okafor' }

export const firmTemplate: A['TemplateBrief'] = { id: 201, name: 'Firm' }
export const litigation: A['TemplateBrief'] = { id: 202, name: 'Litigation' }
export const transactional: A['TemplateBrief'] = { id: 203, name: 'Transactional' }

/** A client as the Clients list and its search give it */
export function clientOf(brief: A['ClientBrief'], over: Partial<A['Client']> = {}): A['Client'] {
  return { ...brief, matters: 2, documents: 500, lastActivity: '2026-11-19T10:12:00Z', ...over }
}

/** GET /v1/admin/clients: a page of clients */
export function clientList(items: A['Client'][], over: Partial<A['ClientList']> = {}): A['ClientList'] {
  return { items, offset: 0, limit: 20, total: items.length, figuresAt: '2026-11-19T10:41:00Z', ...over }
}

/** A client with no matter yet, so nothing to count */
export const marshLane: A['ClientBrief'] = { id: 4, number: 1004, name: 'Marsh Lane Community Trust' }

/** The firm's clients, in the list's own order (by number), each with its matters' figures as matterRows adds them up */
export const clientRows: A['Client'][] = [
  clientOf(ashcombe, { documents: 127, lastActivity: '2026-11-12T09:00:00Z' }),
  clientOf(saltmarsh, { documents: 508, lastActivity: '2026-11-19T10:12:00Z' }),
  clientOf(brightwell, { documents: 221, lastActivity: '2026-11-18T16:20:00Z' }),
  clientOf(kestrel, { documents: 235, lastActivity: '2026-11-19T09:05:00Z' }),
  clientOf(marshLane, { matters: 0, documents: 0, lastActivity: null }),
]

/** GET /v1/admin/clients over clientRows: those whose number starts with `q` or whose name holds it, in any case */
export function clients(query?: Record<string, unknown>): A['ClientList'] {
  const q = String(query?.q ?? '').trim().toLowerCase()
  const items = clientRows.filter((one) => String(one.number).startsWith(q) || one.name.toLowerCase().includes(q))
  return clientList(items, { limit: Number(query?.limit ?? 100) })
}

/** A matter, as the Matters list shows it: 1001.1 unless `over` says otherwise */
export function matterRow(over: Partial<A['MatterRow']> = {}): A['MatterRow'] {
  return {
    id: 11,
    number: '1001.1',
    name: 'Charter Dispute – MV Northern Star',
    client: saltmarsh,
    store: clientWork,
    owner: tom,
    template: litigation,
    documents: 412,
    bytes: Math.round(4.6 * GB),
    lastActivity: '2026-11-19T10:12:00Z',
    folderId: 110,
    ...over,
  }
}

/** The firm's matters, in the list's own order (by client number, then matter number). 1003.2's owner has gone, and it was made from no template. */
export const matterRows: A['MatterRow'][] = [
  matterRow({ id: 91, number: '998.1', name: 'Firm Policies', client: ashcombe, store: firmStore, owner: helen, template: firmTemplate, documents: 86, bytes: 900 * MB, lastActivity: '2026-11-12T09:00:00Z', folderId: 910 }),
  matterRow({ id: 92, number: '998.2', name: 'IT and Systems', client: ashcombe, store: firmStore, owner: sam, template: firmTemplate, documents: 41, bytes: 600 * MB, lastActivity: '2026-11-03T15:30:00Z', folderId: 920 }),
  matterRow(),
  matterRow({ id: 12, number: '1001.2', name: 'Vessel Sale', template: transactional, documents: 96, bytes: Math.round(1.2 * GB), lastActivity: '2026-11-14T11:00:00Z', folderId: 120 }),
  matterRow({ id: 21, number: '1002.1', name: 'Commercial Lease', client: brightwell, owner: helen, template: transactional, documents: 73, bytes: 700 * MB, lastActivity: '2026-11-11T08:45:00Z', folderId: 210 }),
  matterRow({ id: 22, number: '1002.2', name: 'Employment Tribunal', client: brightwell, documents: 148, bytes: Math.round(1.7 * GB), lastActivity: '2026-11-18T16:20:00Z', folderId: 220 }),
  matterRow({ id: 31, number: '1003.1', name: 'Series A Investment', client: kestrel, owner: helen, template: transactional, documents: 204, bytes: Math.round(2.3 * GB), lastActivity: '2026-11-19T09:05:00Z', folderId: 310 }),
  matterRow({ id: 32, number: '1003.2', name: 'Licence Review', client: kestrel, owner: null, template: null, documents: 31, bytes: 300 * MB, lastActivity: '2026-11-06T13:10:00Z', folderId: 320 }),
]

/** GET /v1/admin/matters: a page of matters, their figures worked out at 10:41 on 19 November 2026 */
export function matterList(items: A['MatterRow'][], over: Partial<A['MatterList']> = {}): A['MatterList'] {
  return { items, offset: 0, limit: 100, total: items.length, figuresAt: '2026-11-19T10:41:00Z', ...over }
}

/** GET /v1/admin/matters/next-number, by the client's id: the highest of its matters' numbers plus one, 1 for none */
export const nextMatterNumbers: Record<number, number> = { [ashcombe.id]: 4, [saltmarsh.id]: 3, [brightwell.id]: 3, [kestrel.id]: 3, [marshLane.id]: 1 }

/** A matter, as its page and Create Matter's answer give it: 1004.1, just made, unless `over` says otherwise */
export function matterOf(over: Partial<A['Matter']> = {}): A['Matter'] {
  return {
    id: 34,
    number: '1004.1',
    name: 'Community Hall Lease',
    description: 'Lease of the hall on Marsh Lane',
    client: marshLane,
    store: clientWork,
    owner: helen,
    template: transactional,
    documents: null,
    bytes: null,
    lastActivity: null,
    folderId: 340,
    indexFailed: 0,
    previewsGivenUp: 0,
    ...over,
  }
}

/** A Store no group the templates let in can read yet */
export const regionalOffice: A['Store'] = { id: 5, name: 'Regional Office' }

/** GET /v1/admin/templates: by name, each with its folders and the matters made from it */
export const templates: A['TemplateInfo'][] = [
  { ...firmTemplate, folders: 1, matters: 3 },
  { ...litigation, folders: 6, matters: 3 },
  { ...transactional, folders: 7, matters: 4 },
]

function folderNode(id: number, name: string, children: A['WorkspaceNode'][] = [], over: Partial<A['WorkspaceNode']> = {}): A['WorkspaceNode'] {
  return { id, name, kind: 'folder', ownPermissions: false, children, ...over }
}

function searchFolderNode(id: number, name: string): A['WorkspaceNode'] {
  return { id, name, kind: 'search-folder', ownPermissions: false, children: [] }
}

/** GET /v1/admin/workspaces/{id}/tree for each template: each folder's folders, then its search folders, by name */
export const templateTrees: Record<number, A['WorkspaceNode']> = {
  [firmTemplate.id]: folderNode(firmTemplate.id, 'Firm', [folderNode(2011, 'Policies')], { ownPermissions: true }),
  [litigation.id]: folderNode(
    litigation.id,
    'Litigation',
    [
      folderNode(2021, 'Correspondence'),
      folderNode(2022, 'Evidence', [folderNode(2026, 'Witness Statements')]),
      folderNode(2023, 'Pleadings'),
      folderNode(2024, 'Privileged', [], { ownPermissions: true }),
      searchFolderNode(2025, 'My Recent Docs Here'),
    ],
    { ownPermissions: true },
  ),
  [transactional.id]: folderNode(
    transactional.id,
    'Transactional',
    [
      folderNode(2031, 'Correspondence'),
      folderNode(2032, 'Drafts'),
      folderNode(2033, 'Due Diligence'),
      folderNode(2034, 'Emails'),
      folderNode(2035, 'Executed'),
      searchFolderNode(2036, 'Emails in This Matter'),
      searchFolderNode(2037, 'My Recent Docs Here'),
    ],
    { ownPermissions: true },
  ),
}

export const everyone: A['Group'] = { id: 2, name: 'Everyone', description: 'Everyone in the firm' }
export const partners: A['Group'] = { id: 3, name: 'Partners', description: null }
export const trainees: A['Group'] = { id: 4, name: 'Trainees', description: 'Trainee solicitors' }

/** GET /v1/admin/groups: by name, those whose name holds `q`, in any case */
export function groups(query?: Record<string, unknown>): A['GroupList'] {
  const q = String(query?.q ?? '').trim().toLowerCase()
  const items = [everyone, partners, trainees].filter((one) => one.name.toLowerCase().includes(q))
  return { items, offset: 0, limit: Number(query?.limit ?? 50), total: items.length }
}

/** A row of who can see a Store: a group's, or a person's own group's with `userId` */
export function accessRow(groupId: number, name: string, permissions: A['Permission'][], userId: number | null = null): A['AccessRow'] {
  const level: A['AccessLevel'] = permissions.length === 1 && permissions[0] === 'read' ? 'can-see' : 'custom'
  return { groupId, name, person: userId !== null, userId, level, permissions, owner: false }
}

/** Someone who has left: a disabled account, which GET /v1/users leaves out and the admin find keeps */
export const sally: A['FoundPerson'] = { id: 8, username: 'sally.old', name: 'Sally Oldham', enabled: false }

/** GET /v1/admin/find for `q`: only people, those who have left too, whose name or username holds it */
export function findPeople(q: unknown): A['Found'] {
  const words = String(q ?? '').toLowerCase()
  const everyone: A['FoundPerson'][] = [...[helen, tom, sam].map((one) => ({ ...one, enabled: true })), sally]
  return { clients: [], matters: [], people: everyone.filter((one) => one.name.toLowerCase().includes(words) || one.username.includes(words)) }
}

/** GET /v1/users, the people picker's: anyone whose name or username holds `q`, enabled accounts only */
export function people(q: unknown): S['UserHitPage'] {
  const words = String(q ?? '').toLowerCase()
  const items = [helen, tom, sam].filter((one) => one.name.toLowerCase().includes(words) || one.username.includes(words))
  return { items, offset: 0, limit: 20, total: items.length }
}

export function problem(status: number, code: string, detail = 'A server sentence that must not be shown raw.'): ApiError {
  return new ApiError(status, code, detail, { status, code, detail })
}

export interface Call {
  method: string
  path: string
  query?: Record<string, unknown>
  /** The signal a get was given: a request nobody waits for any more has it aborted */
  signal?: AbortSignal
  /** What a POST, PATCH or PUT sent */
  body?: unknown
}

type Handler = (request: { params: string[]; query?: Record<string, unknown>; body?: unknown; signal?: AbortSignal }) => unknown

export type Routes = Record<string, unknown | Handler>

/** What a signed-in administrator sees. Routes read `METHOD /path`, where `*` matches one segment. */
export function defaultRoutes(): Routes {
  return {
    'GET /v1/me': me,
    'DELETE /v1/session': undefined,
    'GET /v1/admin/dashboard': dashboard,
    'GET /v1/admin/dashboard/usage': usage,
    'GET /v1/admin/find': nothingFound,
    'GET /v1/admin/matters': matterList(matterRows),
    'GET /v1/admin/stores': stores,
    'GET /v1/admin/clients': clientList([]),
    'GET /v1/admin/clients/next-number': { number: 1005 } satisfies A['NextNumber'],
    'GET /v1/users': ({ query }: { query?: Record<string, unknown> }) => people(query?.q),
    'GET /v1/admin/matters/next-number': ({ query }: { query?: Record<string, unknown> }) => ({ number: nextMatterNumbers[Number(query?.clientId)] ?? 1 }) satisfies A['NextNumber'],
    'POST /v1/admin/matters/check': { problems: [] } satisfies A['MatterCheck'],
    'GET /v1/admin/templates': templates,
    'GET /v1/admin/workspaces/*/tree': ({ params }: { params: string[] }) => templateTrees[Number(params[0])] ?? problem(404, 'not-found'),
    'GET /v1/admin/groups': ({ query }: { query?: Record<string, unknown> }) => groups(query),
    'GET /v1/admin/stores/*/access': ({ params }: { params: string[] }) => ({ storeId: Number(params[0]), rows: [] }) satisfies A['StoreAccess'],
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
 * An Api that answers from `routes` (over the defaults); a value that is an Error is thrown, and a path no route has is
 * a 404. A 401 calls onSignedOut first, as createApi's does.
 */
export function fakeApi(over: Routes = {}, onSignedOut?: () => void): FakeApi {
  const routes = { ...defaultRoutes(), ...over }
  const calls: Call[] = []

  async function answer<T>(method: string, path: string, query?: Record<string, unknown>, body?: unknown, signal?: AbortSignal): Promise<T> {
    calls.push({ method, path, query, signal, ...(body === undefined ? {} : { body }) })
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
    send: (method, path, body, query) => answer(method, path, query, body),
    url: (path, query) => {
      const params = new URLSearchParams()
      for (const [name, value] of Object.entries(query ?? {})) if (value !== undefined) params.append(name, String(value))
      const text = params.toString()
      return `/api${path}${text ? `?${text}` : ''}`
    },
  }
}
