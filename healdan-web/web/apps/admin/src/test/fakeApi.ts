// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { ApiError, type adminComponents, type Api, type components } from '@healdan/api'
import { inOrder, levelOf, permissionsOf, type NamedLevel } from '../workspace/levels'
import { placed } from '../workspace/tree'

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

/** A Store as it's named: its id and name */
type StoreName = Pick<A['Store'], 'id' | 'name'>

export const firmStore: StoreName = { id: 3, name: 'Firm' }
export const clientWork: StoreName = { id: 4, name: 'Client Work' }

/** The backends the Stores keep their files on, as a Store names one */
export const originalOnStore: A['StoreBackend'] = { id: 1, name: 'Original', kind: 'local', encrypted: false }
export const wasabiOnStore: A['StoreBackend'] = { id: 2, name: 'Wasabi EU', kind: 's3', encrypted: true }

/** Nothing waiting to reach a Store's backend, and nothing stuck */
export const nothingPending: A['PendingFiles'] = { waiting: 0, waitingBytes: 0, stuck: 0 }

/** A Store as the Stores list and its page give it: on Original, with nothing in it, unless `over` says otherwise */
export function storeInfoOf(store: StoreName, over: Partial<A['StoreInfo']> = {}): A['StoreInfo'] {
  return { id: store.id, name: store.name, matters: 0, documents: 0, bytes: 0, figuresAt: '2026-11-19T10:41:00Z', readers: [], backend: originalOnStore, pending: nothingPending, ...over }
}

/** Client Work: six matters on Wasabi EU, encrypted, that Everyone can see, with three files stuck */
export const clientWorkInfo = storeInfoOf(clientWork, {
  matters: 6,
  documents: 1004,
  bytes: Math.round(11.8 * GB),
  readers: [{ groupId: 2, name: 'Everyone', person: false }],
  backend: wasabiOnStore,
  pending: { waiting: 0, waitingBytes: 0, stuck: 3 },
})

/** Firm: two matters on Original, that Everyone and Helen can see, everything in place */
export const firmInfo = storeInfoOf(firmStore, {
  matters: 2,
  documents: 280,
  bytes: Math.round(2.4 * GB),
  readers: [
    { groupId: 2, name: 'Everyone', person: false },
    { groupId: 105, name: 'Helen Ashcombe', person: true },
  ],
})

/** GET /v1/admin/stores: by name, each with what the Stores list shows; the menus read only the id and the name */
export const stores: A['StoreInfo'][] = [clientWorkInfo, firmInfo]

/** A Store in the Trash: Old Branch, deleted by Sam with its two matters, unless `over` says otherwise */
export function deletedStoreOf(over: Partial<A['DeletedStore']> = {}): A['DeletedStore'] {
  return {
    id: 6,
    name: 'Old Branch',
    trashItemId: 950,
    state: 'deleted',
    matters: 2,
    deletedAt: '2026-11-18T15:00:00Z',
    deletedBy: { id: 10, username: 'sam', name: 'Sam Okafor' },
    removedAt: null,
    destroyAfter: null,
    ...over,
  }
}

/** A Store's backend and files, as GET /v1/admin/stores/{id}/backend answers without a backend to count a move to */
export function storeStorageOf(info: A['StoreInfo'], over: Partial<A['StoreStorage']> = {}): A['StoreStorage'] {
  return { storeId: info.id, on: info.backend !== null, backend: info.backend, files: 0, bytes: 0, pending: info.pending ?? nothingPending, switchPreview: null, ...over }
}

/** Client Work's 3,902 files on Wasabi EU, and Firm's 812 on Original */
export const storeStorage: Record<number, A['StoreStorage']> = {
  [clientWork.id]: storeStorageOf(clientWorkInfo, { files: 3902, bytes: Math.round(11.8 * GB) }),
  [firmStore.id]: storeStorageOf(firmInfo, { files: 812, bytes: Math.round(2.4 * GB) }),
}

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
    figuresAt: null,
    ...over,
  }
}

/** A Store no group the templates let in can read yet, as its create answers it: on Original */
export const regionalOffice: A['Store'] = { id: 5, name: 'Regional Office', backendId: 1 }

/** GET /v1/admin/templates: by name, each with its folders and the matters made from it */
export const templates: A['TemplateInfo'][] = [
  { ...firmTemplate, folders: 1, matters: 3 },
  { ...litigation, folders: 6, matters: 3 },
  { ...transactional, folders: 7, matters: 4 },
]

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

/** A group as GET /v1/admin/groups lists it: no members, not fixed, not `admin`, in no provider's map, unless `over` says */
export function group(id: number, name: string, description: string | null, over: Partial<A['Group']> = {}): A['Group'] {
  return { id, name, description, members: 0, fixed: false, admin: false, mappedBy: [], ...over }
}

export const everyone: A['Group'] = group(2, 'Everyone', 'Everyone in the firm', { members: 12, fixed: true })
export const partners: A['Group'] = group(3, 'Partners', null, { members: 4 })
export const trainees: A['Group'] = group(4, 'Trainees', 'Trainee solicitors', { members: 3 })

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

// A matter's page, its Workspace tab and the workspace templates (Task 20): matter 1001.1's workspace and the Litigation
// template's, who can use their folders, the values they set and the rules of their search folders.

/** A matter, for its page: 1001.1 unless `row` and `over` say otherwise */
export function matterPage(row: A['MatterRow'] = matterRow(), over: Partial<A['Matter']> = {}): A['Matter'] {
  return { ...row, description: 'Charter party dispute over the MV Northern Star.', indexFailed: 0, previewsGivenUp: 0, figuresAt: '2026-11-19T10:41:00Z', ...over }
}

export const wsEveryone: A['Group'] = group(2, 'Everyone', 'Everyone in the firm', { fixed: true })
export const wsGuest: A['Group'] = group(3, 'wsGuest', null)
export const wsPartners: A['Group'] = group(20, 'Partners', null)
export const wsTrainees: A['Group'] = group(21, 'Trainees', null)

/** GET /v1/admin/groups for `q`: the groups whose name holds it, in any case, by name */
export function workspaceGroups(q: unknown): A['GroupList'] {
  const words = String(q ?? '').trim().toLowerCase()
  const items = [wsEveryone, wsGuest, wsPartners, wsTrainees].filter((one) => one.name.toLowerCase().includes(words))
  return { items, offset: 0, limit: 20, total: items.length }
}

/** The level the server gives a row: the spec's three by an exact match, Can See for Read alone, and Custom otherwise */
function accessLevel(permissions: readonly A['Permission'][]): A['AccessLevel'] {
  const level = levelOf(permissions)
  return level === 'custom' && permissions.length === 1 && permissions[0] === 'read' ? 'can-see' : level
}

const grants = (given: NamedLevel | A['Permission'][]) => (typeof given === 'string' ? permissionsOf(given) : inOrder(given))

/** What a folder grants a group, at a level or as exact permissions */
export function groupRow(group: A['Group'], given: NamedLevel | A['Permission'][]): A['AccessRow'] {
  const permissions = grants(given)
  return { groupId: group.id, name: group.name, person: false, userId: null, level: accessLevel(permissions), permissions, owner: false }
}

/** What a folder grants a person, through their own group (100 + their id) */
export function personRow(person: A['PersonBrief'], given: NamedLevel | A['Permission'][], over: Partial<A['AccessRow']> = {}): A['AccessRow'] {
  const permissions = grants(given)
  return { groupId: 100 + person.id, name: person.name, person: true, userId: person.id, level: accessLevel(permissions), permissions, owner: false, ...over }
}

/** A folder's access: its own rows unless `over` says it follows */
export function folderAccess(folderId: number, rows: A['AccessRow'][], over: Partial<A['FolderAccess']> = {}): A['FolderAccess'] {
  return { folderId, ownPermissions: true, followsFolderId: null, rows, canApplyToSubfolders: false, ...over }
}

export function folderNode(id: number, name: string, children: A['WorkspaceNode'][] = [], over: Partial<A['WorkspaceNode']> = {}): A['WorkspaceNode'] {
  return { id, name, kind: 'folder', ownPermissions: false, children, ...over }
}

export function searchNode(id: number, name: string): A['WorkspaceNode'] {
  return { id, name, kind: 'search-folder', ownPermissions: false, children: [] }
}

/** Matter 1001.1's workspace, from its own folder (110): Evidence and Privileged have permissions of their own */
export const charterTree = folderNode(
  110,
  '1001.1 – Charter Dispute – MV Northern Star',
  [
    folderNode(111, 'Correspondence'),
    folderNode(112, 'Drafts'),
    folderNode(113, 'Evidence', [], { ownPermissions: true }),
    folderNode(114, 'Pleadings'),
    folderNode(115, 'Privileged', [folderNode(116, "Counsel's Advice")], { ownPermissions: true }),
    searchNode(118, 'Emails in This Matter'),
    searchNode(119, 'My Recent Docs Here'),
  ],
  { ownPermissions: true },
)

const charterTop = [groupRow(wsEveryone, 'read-write'), groupRow(wsPartners, 'read-write'), personRow(tom, 'full-access', { owner: true })]
const follows = (folderId: number, of: A['FolderAccess']) => folderAccess(folderId, of.rows, { ownPermissions: false, followsFolderId: of.folderId })
const charterOwn = folderAccess(110, charterTop, { canApplyToSubfolders: true })
const privileged = folderAccess(115, [groupRow(wsPartners, 'read-write'), personRow(tom, 'full-access', { owner: true })], { canApplyToSubfolders: true })

/** Who can use each folder of 1001.1's workspace: Evidence grants Everyone only Read (a Store's Can See) and Partners three permissions */
export const charterAccess: Record<number, A['FolderAccess']> = {
  110: charterOwn,
  111: follows(111, charterOwn),
  112: follows(112, charterOwn),
  113: folderAccess(113, [groupRow(wsEveryone, ['read']), groupRow(wsPartners, ['read', 'download', 'write']), personRow(tom, 'full-access', { owner: true })]),
  114: follows(114, charterOwn),
  115: privileged,
  116: follows(116, privileged),
  118: follows(118, charterOwn),
  119: follows(119, charterOwn),
}

/** The Classes a folder can set, and their Subclasses, each Subclass naming its Class */
export const classOptions: A['PropertyOption'][] = ['Correspondence', 'Evidence', 'Pleadings'].map((value) => ({ value, label: value, class: null }))
export const subclassOptions: A['PropertyOption'][] = [
  { value: 'Emails', label: 'Emails', class: 'Correspondence' },
  { value: 'Letters', label: 'Letters', class: 'Correspondence' },
  { value: 'Witness Statements', label: 'Witness Statements', class: 'Evidence' },
]

/** A folder's values: Class, Subclass and Confidential, each inheriting nothing unless `over` says so */
export function folderProperties(folderId: number, over: { docclass?: Partial<A['FolderProperty']>; docsubclass?: Partial<A['FolderProperty']>; confidential?: Partial<A['FolderProperty']> } = {}): A['FolderProperties'] {
  const none = { value: null, source: null, inheritedFrom: null }
  return {
    folderId,
    properties: [
      { name: 'docclass', label: 'Class', type: 'string', ...none, options: classOptions, ...over.docclass },
      { name: 'docsubclass', label: 'Subclass', type: 'string', ...none, options: subclassOptions, ...over.docsubclass },
      { name: 'confidential', label: 'Confidential', type: 'boolean', ...none, options: null, ...over.confidential },
    ],
  }
}

/** The values each folder of 1001.1 sets: the install's default Class at the top, Correspondence's own, and Counsel's Advice's from Privileged */
export const charterProperties: Record<number, A['FolderProperties']> = {
  110: folderProperties(110, { docclass: { value: 'Correspondence', source: 'default' } }),
  111: folderProperties(111, { docclass: { value: 'Correspondence', source: 'local' }, docsubclass: { value: 'Letters', source: 'local' } }),
  115: folderProperties(115, { docclass: { value: 'Evidence', source: 'local' }, confidential: { value: true, source: 'local' } }),
  116: folderProperties(116, {
    docclass: { value: 'Evidence', source: 'inherited', inheritedFrom: { id: 115, name: 'Privileged' } },
    confidential: { value: true, source: 'inherited', inheritedFrom: { id: 115, name: 'Privileged' } },
  }),
}

export const searchFolders: Record<number, A['SearchFolder']> = {
  118: { id: 118, name: 'Emails in This Matter', rule: { days: 30, onlyMine: false, kind: 'emails', docClass: null, docSubclass: null } },
  119: { id: 119, name: 'My Recent Docs Here', rule: { days: 14, onlyMine: true, kind: 'all', docClass: null, docSubclass: null } },
  2025: { id: 2025, name: 'My Recent Docs Here', rule: { days: 14, onlyMine: true, kind: 'all', docClass: null, docSubclass: null } },
}

/** GET /v1/admin/templates: by name */
export const templateInfos: A['TemplateInfo'][] = [
  { id: 201, name: 'Firm', folders: 4, matters: 2 },
  { id: 202, name: 'Litigation', folders: 5, matters: 2 },
  { id: 203, name: 'Transactional', folders: 6, matters: 4 },
]

/** The Litigation template's workspace (202): Privileged lets Trainees write, which its parent doesn't */
export const litigationTree = folderNode(
  202,
  'Litigation',
  [folderNode(2021, 'Correspondence'), folderNode(2022, 'Evidence'), folderNode(2023, 'Pleadings'), folderNode(2024, 'Privileged', [], { ownPermissions: true }), searchNode(2025, 'My Recent Docs Here')],
  { ownPermissions: true },
)

const litigationOwn = folderAccess(202, [groupRow(wsEveryone, 'read-write'), groupRow(wsPartners, 'read-write')], { canApplyToSubfolders: true })

export const litigationAccess: Record<number, A['FolderAccess']> = {
  202: litigationOwn,
  2021: follows(2021, litigationOwn),
  2022: follows(2022, litigationOwn),
  2023: follows(2023, litigationOwn),
  2024: folderAccess(2024, [groupRow(wsPartners, 'read-write'), groupRow(wsTrainees, 'read-write')]),
  2025: follows(2025, litigationOwn),
}

/** What New Matter would refuse in a matter made from Litigation */
export const litigationProblems: A['TemplateProblem'][] = [
  { code: 'wider-than-parent', message: 'Privileged grants more than its parent (group Trainees: WRITE).', folderId: 2024, storeId: null, groupId: 21, groupName: wsTrainees.name },
  { code: 'store-unreadable', message: "Trainees could read a matter made from it, but can't read the Store Firm.", folderId: null, storeId: 3, groupId: 21, groupName: wsTrainees.name },
]

/**
 * What a workspace folder's or a template's delete answers: the Trash item it made, which restores a matter's folder
 * (`POST /v1/trash/{id}/restore`) and answers a template's, or a folder of one, `409 place-gone`
 */
export function deletedFolder(over: Partial<A['DeletedFolder']> = {}): A['DeletedFolder'] {
  return { trashItemId: 901, folders: 0, documents: 0, ...over }
}

/** What `POST /v1/trash/{id}/restore` (the shared contract's) answers for a folder of 1001.1 put back in the matter's own folder */
export function restoredFolder(trashItemId: number, name: string): S['RestoredItem'] {
  const matter: S['MatterRef'] = { id: 11, display: '1001.1 – Charter Dispute – MV Northern Star', client: saltmarsh }
  const store: S['FolderRef'] = { id: clientWork.id, name: clientWork.name, kind: 'store' }
  const top: S['FolderRef'] = { id: 110, name: charterTree.name, kind: 'matter' }
  return {
    place: { type: 'folder', id: top.id, name: top.name, kind: 'matter', matter, store: clientWork, path: [store, top], canFile: true, canRename: false, canMove: false, canDelete: false },
    item: {
      id: trashItemId,
      type: 'folder',
      name,
      docNumber: null,
      kind: 'folder',
      size: null,
      contents: { folders: 0, documents: 0 },
      deletedFrom: { folder: top, path: [store.name, top.name] },
      matter,
      deletedBy: { id: me.id, name: me.name },
      deletedAt: '2026-11-20T10:00:00Z',
      removedBy: null,
      removedAt: null,
      destroyAfter: null,
    },
  }
}

const notFound = () => problem(404, 'not-found')

// Storage (Phase 3): the firm's backends, as the Storage tab shows them, the files with no Store, and the settings.

/** A backend, as GET /v1/admin/backends/{id} answers it: Archive, an empty local folder no Store uses, unless `over` says otherwise */
export function backendOf(over: Partial<A['Backend']> = {}): A['Backend'] {
  return {
    id: 3,
    name: 'Archive',
    kind: 'local',
    original: false,
    path: '/mnt/archive',
    endpoint: null,
    region: null,
    bucket: null,
    prefix: null,
    pathStyle: false,
    accessKey: null,
    hasSecret: false,
    encrypted: false,
    keyConfirmed: false,
    offered: true,
    inUse: false,
    files: 0,
    bytes: 0,
    status: 'ok',
    error: null,
    since: '2026-11-19T08:00:00Z',
    keyLastShown: null,
    keyLastShownBy: null,
    stores: [],
    storesInTrash: [],
    ...over,
  }
}

/** Original, which keeps Firm's files, every Store with no other backend */
export const originalBackend = backendOf({ id: 1, name: 'Original', original: true, path: '/opt/logicaldoc/repository/docs', inUse: true, files: 812, bytes: Math.round(2.4 * GB), stores: ['Firm'] })

/** Wasabi EU, the encrypted S3 bucket Client Work keeps its files in, its recovery key confirmed and last shown to Sam */
export const wasabiBackend = backendOf({
  id: 2,
  name: 'Wasabi EU',
  kind: 's3',
  path: null,
  endpoint: 'https://s3.eu-central-1.wasabisys.com',
  region: 'eu-central-1',
  bucket: 'ashcombe-reeve',
  prefix: 'healdan/',
  pathStyle: true,
  accessKey: 'WASABIACCESSKEY01',
  hasSecret: true,
  encrypted: true,
  keyConfirmed: true,
  inUse: true,
  files: 3902,
  bytes: Math.round(11.8 * GB),
  keyLastShown: '2026-10-02T09:15:00Z',
  keyLastShownBy: 'sam',
  stores: ['Client Work'],
})

/** Archive, an empty folder not checked since the server started */
export const archiveBackend = backendOf({ status: 'unchecked', since: null })

/** The office's NAS, encrypted, whose recovery key nobody has confirmed, failing as its share didn't mount */
export const nasBackend = backendOf({
  id: 4,
  name: 'Office NAS',
  path: '/mnt/nas',
  encrypted: true,
  keyConfirmed: false,
  offered: false,
  status: 'failing',
  error: 'The marker of this backend is missing.',
  since: '2026-11-19T09:30:00Z',
})

/** GET /v1/admin/backends: Original first, then the others by id, and nothing waiting or stuck with no Store */
export function backendOverview(over: Partial<A['BackendOverview']> = {}): A['BackendOverview'] {
  return { on: true, backends: [originalBackend, wasabiBackend, archiveBackend, nasBackend], otherFiles: { waiting: 0, waitingBytes: 0, stuck: 0 }, ...over }
}

/** GET /v1/admin/backends on a server that keeps its files in LogicalDOC's own store */
export const storageOff: A['BackendOverview'] = { on: false, backends: [], otherFiles: { waiting: 0, waitingBytes: 0, stuck: 0 } }

/** GET /v1/admin/storage/settings: a 30-day holding period, a 20 GB read cache and 4 parallel transfers, with the server's limits */
export function storageSettings(over: Partial<A['StorageSettings']> = {}): A['StorageSettings'] {
  return {
    on: true,
    holdDays: 30,
    cacheGb: 20,
    transfers: 4,
    limits: { holdDays: { min: 1, max: 365 }, cacheGb: { min: 1, max: 10000 }, transfers: { min: 1, max: 16 } },
    ...over,
  }
}

/** A recovery key as the server writes one out: 13 groups of four, from 0-9 and A-Z without I, L, O or U */
export const RECOVERY_KEY = '7K3M 9QXR 2HTW 4PZB 8VJC 6NDF 1GSY 5EAR 3WKQ 0TMH 9BXZ 2CVN 4JP8'

/** GET /v1/admin/backends/2/key-events: newest first, the showing at its add last */
export const wasabiKeyEvents: A['KeyEvent'][] = [
  { event: 'shown', at: '2026-10-02T09:15:00Z', userId: sam.id, username: 'sam' },
  { event: 'entered', at: '2026-09-30T16:40:00Z', userId: me.id, username: 'admin' },
  { event: 'shown', at: '2026-09-01T11:00:00Z', userId: me.id, username: 'admin' },
]

/** A file the mover gave up on: version 1.2 of document 10388, unless `over` says otherwise */
export function stuckFile(over: Partial<A['StuckFile']> = {}): A['StuckFile'] {
  return { docId: 10388, fileName: 'Heads of Terms.pdf', resource: '1.2', attempts: 10, lastError: 'Access denied by the S3 service.', ...over }
}

/** What the Backends page and a backend's page read, as the defaults answer them */
export function storageRoutes(): Routes {
  const all = backendOverview().backends
  return {
    'GET /v1/admin/backends': backendOverview(),
    'GET /v1/admin/backends/*': ({ params }: { params: string[] }) => all.find((one) => one.id === Number(params[0])) ?? notFound(),
    'GET /v1/admin/backends/*/key-events': ({ params }: { params: string[] }) => (Number(params[0]) === wasabiBackend.id ? wasabiKeyEvents : []),
    'GET /v1/admin/storage/settings': storageSettings(),
    'GET /v1/admin/storage/stuck-files': [],
  }
}

/**
 * What the Stores list and a Store's page read, as the defaults answer them: Client Work and Firm, none in the Trash, each
 * Store's backend and files (with what a switch to another backend would move: every file not on it, as the server
 * counts them), no stuck files listed, and who can see each Store, its readers, each at Can See.
 */
export function storeRoutes(): Routes {
  const byId = (params: string[]) => stores.find((one) => one.id === Number(params[0]))
  return {
    // Before /deleted, which it matches too: of two routes that match, the later wins
    'GET /v1/admin/stores/*': ({ params }: { params: string[] }) => byId(params) ?? notFound(),
    'GET /v1/admin/stores/deleted': [],
    'GET /v1/admin/stores/*/backend': ({ params, query }: { params: string[]; query?: Record<string, unknown> }) => {
      const storage = storeStorage[Number(params[0])]
      if (storage === undefined) return notFound()
      if (query?.backendId === undefined) return storage
      const backendId = Number(query.backendId)
      const moving = storage.backend?.id === backendId ? { files: 0, bytes: 0 } : { files: storage.files, bytes: storage.bytes }
      return { ...storage, switchPreview: { backendId, ...moving } } satisfies A['StoreStorage']
    },
    'GET /v1/admin/stores/*/stuck-files': [],
    'GET /v1/admin/stores/*/access': ({ params }: { params: string[] }) => {
      const store = byId(params)
      // A Store the fixtures don't hold is new, and lets nobody in but the administrators
      if (store === undefined) return { storeId: Number(params[0]), rows: [] } satisfies A['StoreAccess']
      const rows = store.readers.map((reader) => accessRow(reader.groupId, reader.name, ['read'], reader.person ? reader.groupId - 100 : null))
      return { storeId: store.id, rows } satisfies A['StoreAccess']
    },
  }
}

/**
 * What a matter's page and a template's read, to spread over the defaults: matter 11 (1001.1), the templates, the
 * workspaces' trees, their folders' access and values, their search folders, the groups, and what a folder holds.
 */
export function workspaceRoutes(): Routes {
  const trees: Record<number, A['WorkspaceNode']> = { 110: charterTree, 202: litigationTree }
  const access: Record<number, A['FolderAccess']> = { ...charterAccess, ...litigationAccess }
  return {
    'GET /v1/admin/matters/11': matterPage(),
    'GET /v1/admin/workspaces/*/tree': ({ params }: { params: string[] }) => trees[Number(params[0])] ?? notFound(),
    'GET /v1/admin/folders/*/access': ({ params }: { params: string[] }) => access[Number(params[0])] ?? notFound(),
    'GET /v1/admin/folders/*/properties': ({ params }: { params: string[] }) => {
      const id = Number(params[0])
      if (searchFolders[id]) return problem(409, 'refused', "A search folder sets no values.")
      return charterProperties[id] ?? folderProperties(id)
    },
    'GET /v1/admin/search-folders/*': ({ params }: { params: string[] }) => searchFolders[Number(params[0])] ?? notFound(),
    'GET /v1/admin/groups': ({ query }: { query?: Record<string, unknown> }) => workspaceGroups(query?.q),
    'GET /v1/admin/templates': templateInfos,
    'GET /v1/admin/templates/*': ({ params }: { params: string[] }) => templateInfos.find((one) => one.id === Number(params[0])) ?? notFound(),
    'GET /v1/admin/templates/*/problems': ({ params }: { params: string[] }) => (Number(params[0]) === 202 ? litigationProblems : []),
    'GET /v1/folders/*/contents': { folders: 8, documents: 412 } satisfies S['TrashContents'],
  }
}

// People (Phase 4): the firm's people, as the Users list and a person's page give them, the groups they're in, the options
// New User and Edit offer, and a person's devices and check-outs.

/** The groups a person can be in, besides Everyone, Partners and Trainees: `admin`, `guest`, and one a provider maps */
export const adminGroup: A['Group'] = group(1, 'admin', 'Administrators', { members: 2, fixed: true, admin: true })
export const guestGroup: A['Group'] = group(7, 'guest', 'Read-only access', { members: 1, fixed: true })
export const feeEarners: A['Group'] = group(8, 'Fee Earners', null, { members: 3, mappedBy: ['Authentik'] })

/** GET /v1/admin/groups as a person's groups and the Users list's Group filter see it: every group, by name */
export function peopleGroups(query?: Record<string, unknown>): A['GroupList'] {
  const q = String(query?.q ?? '').trim().toLowerCase()
  const all = [adminGroup, everyone, feeEarners, guestGroup, partners, trainees]
  const items = all.filter((one) => one.name.toLowerCase().includes(q))
  return { items, offset: 0, limit: Number(query?.limit ?? 50), total: items.length }
}

const groupBrief = (one: A['Group']): A['GroupBrief'] => ({ id: one.id, name: one.name })

/** The sign-in provider the firm's people sign in through */
export const authentik: A['ProviderBrief'] = { id: 'authentik', label: 'Authentik' }

/** A person as the Users list gives them: Priya, who signs in with a password, unless `over` says otherwise */
export function userRow(over: Partial<A['UserRow']> = {}): A['UserRow'] {
  return {
    id: 7,
    username: 'priya',
    name: 'Priya Nair',
    firstName: 'Priya',
    lastName: 'Nair',
    email: 'priya.nair@example.test',
    groups: [groupBrief(everyone), groupBrief(trainees)],
    signsInWith: { kind: 'password', provider: null },
    status: 'active',
    blockedUntil: null,
    lastSignIn: '2026-11-19T10:39:00Z',
    devices: 1,
    readOnly: false,
    admin: false,
    ...over,
  }
}

/** The server would refuse none of a person's page's actions */
export const noRefusals: A['UserRefusals'] = { disable: null, enable: null, resetPassword: null, leaveAdmin: null }

/** A person, for their page, from their row: a password set on 3 October, unless `over` says otherwise */
export function userOf(row: A['UserRow'] = userRow(), over: Partial<A['User']> = {}): A['User'] {
  const provider = row.signsInWith.kind === 'provider'
  return {
    ...row,
    language: 'en',
    timeZone: 'Europe/London',
    created: '2026-10-01T09:00:00Z',
    passwordChanged: provider ? null : '2026-10-03T09:00:00Z',
    mustChangePassword: false,
    checkedOut: 0,
    mattersOwned: 0,
    refusals: provider ? { ...noRefusals, resetPassword: 'An account that signs in through a sign-in provider has no password here.' } : noRefusals,
    ...over,
  }
}

export const priyaRow = userRow()
export const helenRow = userRow({
  id: 5,
  username: 'helen',
  name: 'Helen Ashcombe',
  firstName: 'Helen',
  lastName: 'Ashcombe',
  email: 'helen@example.test',
  groups: [groupBrief(everyone), groupBrief(feeEarners), groupBrief(partners)],
  signsInWith: { kind: 'provider', provider: authentik },
  lastSignIn: '2026-11-19T09:02:00Z',
  devices: 2,
})
export const jamieRow = userRow({
  id: 12,
  username: 'jclarke',
  name: 'Jamie Clarke',
  firstName: 'Jamie',
  lastName: 'Clarke',
  email: null,
  status: 'blocked',
  blockedUntil: '2026-11-19T11:15:00Z',
  lastSignIn: '2026-11-18T16:20:00Z',
})
export const sallyRow = userRow({
  id: 8,
  username: 'sally.old',
  name: 'Sally Oldham',
  firstName: 'Sally',
  lastName: 'Oldham',
  email: 'sally@example.test',
  status: 'disabled',
  lastSignIn: '2026-09-30T17:00:00Z',
  devices: 0,
})
export const samRow = userRow({
  id: 10,
  username: 'sam',
  name: 'Sam Okafor',
  firstName: 'Sam',
  lastName: 'Okafor',
  email: 'sam@example.test',
  groups: [groupBrief(adminGroup), groupBrief(everyone)],
  lastSignIn: '2026-11-19T08:30:00Z',
  devices: 0,
  admin: true,
})
/** The `admin` account, which is the caller's own (me) */
export const adminRow = userRow({
  id: me.id,
  username: 'admin',
  name: 'Alex Morgan',
  firstName: 'Alex',
  lastName: 'Morgan',
  email: 'alex@example.test',
  groups: [groupBrief(adminGroup), groupBrief(everyone)],
  lastSignIn: '2026-11-19T10:41:00Z',
  devices: 0,
  admin: true,
})
/** A read-only account, which has never signed in */
export const ronnieRow = userRow({
  id: 13,
  username: 'rhale',
  name: 'Ronnie Hale',
  firstName: 'Ronnie',
  lastName: 'Hale',
  email: 'ronnie@example.test',
  groups: [groupBrief(guestGroup)],
  lastSignIn: null,
  devices: 0,
  readOnly: true,
})

/** The firm's people, by name, as GET /v1/admin/users gives them unless asked otherwise */
export const userRows: A['UserRow'][] = [adminRow, helenRow, jamieRow, priyaRow, ronnieRow, samRow, sallyRow]

/** The refusals the server gives the caller's own account, the `admin` account */
export const ownRefusals: A['UserRefusals'] = {
  disable: "The admin account can't be disabled.",
  enable: null,
  resetPassword: 'Change your own password from your person menu.',
  leaveAdmin: "The admin account is always in admin.",
}

/** Each person, as their page gives them */
export const users: Record<number, A['User']> = {
  [adminRow.id]: userOf(adminRow, { refusals: ownRefusals }),
  [helenRow.id]: userOf(helenRow, { checkedOut: 1, mattersOwned: 3, timeZone: null }),
  [jamieRow.id]: userOf(jamieRow),
  [priyaRow.id]: userOf(priyaRow, { checkedOut: 2 }),
  [ronnieRow.id]: userOf(ronnieRow, { mustChangePassword: true }),
  [samRow.id]: userOf(samRow),
  [sallyRow.id]: userOf(sallyRow),
}

/** GET /v1/admin/users: the rows `q`, `groupId` and `status` keep, in the order `sort` and `order` ask, a page from `offset` */
export function userList(query?: Record<string, unknown>, rows: A['UserRow'][] = userRows): A['UserList'] {
  const q = String(query?.q ?? '').trim().toLowerCase()
  const groupId = query?.groupId === undefined ? undefined : Number(query.groupId)
  const kept = rows.filter(
    (row) =>
      (q === '' || [row.username, row.email ?? '', row.name].some((text) => text.toLowerCase().includes(q))) &&
      (groupId === undefined || row.groups.some((one) => one.id === groupId)) &&
      (query?.status === undefined || row.status === query.status),
  )
  const sort = String(query?.sort ?? 'name')
  const key = (row: A['UserRow']) => (sort === 'username' ? row.username.toLowerCase() : sort === 'lastSignIn' ? (row.lastSignIn ?? '') : row.name.toLowerCase())
  const sorted = [...kept].sort((a, b) => key(a).localeCompare(key(b)))
  if (query?.order === 'desc') sorted.reverse()
  // Someone who has never signed in comes last whichever the order
  const ordered = sort === 'lastSignIn' ? [...sorted.filter((row) => row.lastSignIn !== null), ...sorted.filter((row) => row.lastSignIn === null)] : sorted
  const offset = Number(query?.offset ?? 0)
  const limit = Number(query?.limit ?? 100)
  return { items: ordered.slice(offset, offset + limit), offset, limit, total: ordered.length }
}

/** GET /v1/admin/users/options: three languages, a few time zones, no sign-in provider on, and outgoing email set up */
export function userOptions(over: Partial<A['UserOptions']> = {}): A['UserOptions'] {
  return {
    languages: [
      { code: 'en', name: 'English' },
      { code: 'en_US', name: 'English (United States)' },
      { code: 'de', name: 'German' },
    ],
    defaultLanguage: 'en',
    timeZones: ['America/New_York', 'Asia/Tokyo', 'Europe/Berlin', 'Europe/London', 'Europe/Paris'],
    providers: [],
    outgoingEmail: true,
    ...over,
  }
}

/** A device a person is signed in on: the Healdan Helper on Helen's MacBook, unless `over` says otherwise */
export function userDevice(over: Partial<A['UserDevice']> = {}): A['UserDevice'] {
  return {
    id: 501,
    name: 'Healdan Helper on MacBook',
    device: 'mac-7f3a',
    provider: authentik,
    created: '2026-11-02T08:00:00Z',
    lastUsed: '2026-11-19T10:33:00Z',
    expires: '2027-02-02T08:00:00Z',
    ...over,
  }
}

/** A document a person has checked out: Heads of Terms, in 1001.1, on Helen's MacBook in Word, unless `over` says otherwise */
export function userCheckout(over: Partial<A['UserCheckout']> = {}): A['UserCheckout'] {
  return {
    docNumber: 10388,
    fileVersion: '2.1',
    title: 'Heads of Terms.docx',
    matter: { id: 11, number: '1001.1', name: 'Charter Dispute' },
    since: '2026-11-18T14:05:00Z',
    device: 'Healdan Helper on MacBook',
    deviceId: 'mac-7f3a',
    app: 'Word',
    ...over,
  }
}

/** Each person's devices and check-outs, as their page lists them */
export const userDevices: Record<number, A['UserDevice'][]> = {
  [helenRow.id]: [userDevice(), userDevice({ id: 502, name: 'Healdan Helper on Office PC', device: 'win-22c1', lastUsed: '2026-11-18T17:10:00Z' })],
  [priyaRow.id]: [userDevice({ id: 503, name: 'Healdan Helper on ThinkPad', device: 'linux-9e0b', provider: null })],
  [jamieRow.id]: [userDevice({ id: 504, name: 'Healdan Helper on iMac', device: null, provider: null })],
}
export const userCheckouts: Record<number, A['UserCheckout'][]> = {
  [helenRow.id]: [userCheckout()],
  [priyaRow.id]: [
    userCheckout({ docNumber: 10412, title: 'Witness Statement.docx', fileVersion: '1.3', device: 'Healdan Helper on ThinkPad', deviceId: 'linux-9e0b', app: 'LibreOffice', since: '2026-11-19T09:00:00Z' }),
    userCheckout({ docNumber: 10398, title: 'Board Minutes.xlsx', fileVersion: '1.0', matter: null, device: null, deviceId: null, app: null, since: null }),
  ],
}

/** What the Users list and a person's page read, to spread over the defaults: the people, their options, devices and check-outs, and every group */
export function peopleRoutes(): Routes {
  return {
    'GET /v1/admin/users': ({ query }: { query?: Record<string, unknown> }) => userList(query),
    'GET /v1/admin/users/*': ({ params }: { params: string[] }) => users[Number(params[0])] ?? notFound(),
    // After the person's route, which it matches too: of two routes that match, the later wins
    'GET /v1/admin/users/options': userOptions(),
    'GET /v1/admin/users/*/devices': ({ params }: { params: string[] }) => (users[Number(params[0])] ? (userDevices[Number(params[0])] ?? []) : notFound()),
    'GET /v1/admin/users/*/checkouts': ({ params }: { params: string[] }) => (users[Number(params[0])] ? (userCheckouts[Number(params[0])] ?? []) : notFound()),
    'GET /v1/admin/groups': ({ query }: { query?: Record<string, unknown> }) => peopleGroups(query),
  }
}

// Groups (Phase 4): the Groups list, a group's page and its members, from the people above: each group's members are the
// people whose row names it, so the counts and the lists agree.

/** LogicalDOC's `publisher`, one of the fixed groups, with nobody in it: LogicalDOC gives it a negative id */
export const publisherGroup: A['Group'] = group(-10000, 'publisher', null, { fixed: true })

/** A person as a group's members list gives them */
export function memberOf(row: A['UserRow']): A['GroupMember'] {
  return { id: row.id, username: row.username, name: row.name, status: row.status, readOnly: row.readOnly }
}

/** The people in a group, by name, as GET /v1/admin/groups/{id}/members gives them */
export function groupMembers(groupId: number, rows: A['UserRow'][] = userRows): A['GroupMember'][] {
  return rows.filter((row) => row.groups.some((one) => one.id === groupId)).map(memberOf)
}

/** Every group, by name, as the Groups list gives them, each counting the people in it */
export const groupRows: A['Group'][] = [adminGroup, everyone, feeEarners, guestGroup, partners, publisherGroup, trainees].map((one) => ({
  ...one,
  members: groupMembers(one.id).length,
}))

/** How many folders give each group a permission of its own */
const GROUP_FOLDERS: Record<number, number> = { [everyone.id]: 14, [feeEarners.id]: 2, [guestGroup.id]: 1, [partners.id]: 6, [trainees.id]: 3 }

/** A group, for its page: its row, with the folders that give it a permission */
export function groupDetailOf(row: A['Group'], over: Partial<A['GroupDetail']> = {}): A['GroupDetail'] {
  return { ...row, folders: GROUP_FOLDERS[row.id] ?? 0, ...over }
}

/** Each group, as its page gives it */
export const groupDetails: Record<number, A['GroupDetail']> = Object.fromEntries(groupRows.map((row) => [row.id, groupDetailOf(row)]))

/** A page of `rows` from the query's `offset`, `limit` at most, of those `q` keeps, by what `text` gives of each */
function pageOf<T>(rows: T[], query: Record<string, unknown> | undefined, text: (row: T) => string[], limit: number) {
  const q = String(query?.q ?? '').trim().toLowerCase()
  const kept = q === '' ? rows : rows.filter((row) => text(row).some((one) => one.toLowerCase().includes(q)))
  const offset = Number(query?.offset ?? 0)
  const size = Number(query?.limit ?? limit)
  return { items: kept.slice(offset, offset + size), offset, limit: size, total: kept.length }
}

/** GET /v1/admin/groups: the groups whose name holds `q`, in any case, a page from `offset` */
export function groupList(query?: Record<string, unknown>, rows: A['Group'][] = groupRows): A['GroupList'] {
  return pageOf(rows, query, (row) => [row.name], 50)
}

/** GET /v1/admin/groups/{id}/members: the members whose name or username holds `q`, a page from `offset` */
export function memberList(query?: Record<string, unknown>, members: A['GroupMember'][] = []): A['GroupMemberList'] {
  return pageOf(members, query, (member) => [member.name, member.username], 100)
}

/** GET /v1/users, the people picker's, over the people above: those who can sign in whose name or username holds `q` */
export function peopleWhoSignIn(q: unknown): S['UserHitPage'] {
  const words = String(q ?? '').toLowerCase()
  const items = userRows
    .filter((row) => row.status !== 'disabled' && (row.name.toLowerCase().includes(words) || row.username.includes(words)))
    .map(({ id, username, name }) => ({ id, username, name }))
  return { items, offset: 0, limit: 20, total: items.length }
}

/** What the Groups list and a group's page read, to spread over the defaults: the people's routes, every group and their members */
export function groupRoutes(): Routes {
  return {
    ...peopleRoutes(),
    'GET /v1/users': ({ query }: { query?: Record<string, unknown> }) => peopleWhoSignIn(query?.q),
    'GET /v1/admin/groups': ({ query }: { query?: Record<string, unknown> }) => groupList(query),
    'GET /v1/admin/groups/*': ({ params }: { params: string[] }) => groupDetails[Number(params[0])] ?? notFound(),
    'GET /v1/admin/groups/*/members': ({ params, query }: { params: string[]; query?: Record<string, unknown> }) =>
      groupDetails[Number(params[0])] ? memberList(query, groupMembers(Number(params[0]))) : notFound(),
  }
}

// Monitoring (Phase 5): the faults the server logged and its logs, what was done, and the background jobs, each screen's
// fixtures in a section of its own.

// Monitoring: Errors and the Server Log

/** A kind of fault as the Errors list gives it: the preview that timed out, seen 6 times today, unless `over` says otherwise */
export function errorRow(over: Partial<A['ErrorRow']> = {}): A['ErrorRow'] {
  return {
    id: 12,
    message: 'Preview conversion timed out after 180 seconds, doc 10412 version 3',
    area: 'Previews',
    times: 6,
    firstSeen: '2026-11-18T14:02:00Z',
    lastSeen: '2026-11-19T10:41:00Z',
    resolved: false,
    ...over,
  }
}

/** The four kinds of fault seen in the last 24 hours, the one last seen first: 14 errors of 4 kinds, the last resolved */
export const errorRows: A['ErrorRow'][] = [
  errorRow(),
  errorRow({ id: 13, message: 'Upload to Wasabi EU failed: connection reset\nThe mover tries again in 5 minutes.', area: 'Storage', times: 5, firstSeen: '2026-11-19T09:12:00Z', lastSeen: '2026-11-19T10:37:00Z' }),
  errorRow({ id: 14, message: "Couldn't read PDF text: the file is encrypted", area: 'Search Index', times: 2, firstSeen: '2026-11-19T08:30:00Z', lastSeen: '2026-11-19T08:31:00Z' }),
  errorRow({ id: 15, message: 'Outgoing email refused: authentication failed', area: 'Email', times: 1, firstSeen: '2026-11-18T17:20:00Z', lastSeen: '2026-11-18T17:20:00Z', resolved: true }),
]

/**
 * GET /v1/admin/errors over `rows`: those its words (in the message or the logger's name, as errorDetails gives it), area
 * and Unresolved keep, a page from `offset`, their times added up. The window isn't looked at: the fake has no hour counts.
 */
export function errorList(query?: Record<string, unknown>, rows: A['ErrorRow'][] = errorRows): A['ErrorList'] {
  const q = String(query?.q ?? '').trim().toLowerCase()
  const said = (row: A['ErrorRow']) => [row.message, errorDetails[row.id]?.logger ?? ''].some((text) => text.toLowerCase().includes(q))
  const kept = rows.filter((row) => (q === '' || said(row)) && (query?.area === undefined || row.area === query.area) && (query?.unresolved !== true || !row.resolved))
  const offset = Number(query?.offset ?? 0)
  const limit = Number(query?.limit ?? 100)
  return { items: kept.slice(offset, offset + limit).map((row) => ({ ...row })), offset, limit, total: kept.length, errors: kept.reduce((sum, row) => sum + row.times, 0) }
}

/** A fault, for its details: its row's facts, logged through the preview worker into the main log, unless `over` says otherwise */
export function errorDetailOf(row: A['ErrorRow'], over: Partial<A['ErrorDetail']> = {}): A['ErrorDetail'] {
  return {
    id: row.id,
    level: 'ERROR',
    area: row.area,
    logger: 'com.logicaldoc.healdan.preview.PreviewWorker',
    message: row.message,
    stack: null,
    documents: [],
    total: row.times,
    firstSeen: row.firstSeen,
    lastSeen: row.lastSeen,
    resolved: row.resolved,
    resolvedBy: row.resolved ? 'sam' : null,
    resolvedAt: row.resolved ? '2026-11-19T09:00:00Z' : null,
    log: 'DMS',
    ...over,
  }
}

/** Each fault of errorRows, as GET /v1/admin/errors/{id} gives it */
export const errorDetails: Record<number, A['ErrorDetail']> = {
  12: errorDetailOf(errorRows[0]!, {
    stack: [
      'com.logicaldoc.healdan.preview.PreviewTimeoutException: soffice did not finish within 180 s',
      '\tat com.logicaldoc.healdan.preview.OfficeConverter.convert(OfficeConverter.java:142)',
      '\tat com.logicaldoc.healdan.preview.PreviewWorker.run(PreviewWorker.java:366)',
    ].join('\n'),
    documents: [
      { id: 5012, docNumber: 10412, fileName: 'Witness Statement.docx' },
      { id: 5013, docNumber: null, fileName: 'Board Minutes.xlsx' },
      { id: 4999, docNumber: null, fileName: null },
    ],
    total: 31,
  }),
  13: errorDetailOf(errorRows[1]!, { logger: 'com.logicaldoc.healdan.storage.Mover', stack: 'java.net.SocketException: Connection reset' }),
  14: errorDetailOf(errorRows[2]!, { level: 'FATAL', logger: 'com.logicaldoc.core.parser.PDFParser', log: 'IndexerTask' }),
  15: errorDetailOf(errorRows[3]!, { logger: 'com.logicaldoc.core.communication.EMailSender', log: null }),
}

/**
 * The Errors screen's routes, to spread over the defaults: the list and each fault over copies of errorRows and
 * errorDetails of their own, which Mark Resolved and Reopen change, so the list read after one follows it
 */
export function errorRoutes(): Routes {
  const rows = errorRows.map((row) => ({ ...row }))
  const details = new Map(Object.values(errorDetails).map((detail) => [detail.id, { ...detail }]))
  function mark(id: number, resolved: boolean): A['ErrorDetail'] | ApiError {
    const detail = details.get(id)
    if (detail === undefined) return notFound()
    const changed = { ...detail, resolved, resolvedBy: resolved ? 'admin' : null, resolvedAt: resolved ? '2026-11-19T10:45:00Z' : null }
    details.set(id, changed)
    // A row of its own, as a server's answer is: the list's answer read before keeps what it said
    rows.forEach((row, index) => {
      if (row.id === id) rows[index] = { ...row, resolved }
    })
    return changed
  }
  return {
    'GET /v1/admin/errors': ({ query }: { query?: Record<string, unknown> }) => errorList(query, rows),
    'GET /v1/admin/errors/*': ({ params }: { params: string[] }) => details.get(Number(params[0])) ?? notFound(),
    'POST /v1/admin/errors/*/resolve': ({ params }: { params: string[] }) => mark(Number(params[0]), true),
    'POST /v1/admin/errors/*/reopen': ({ params }: { params: string[] }) => mark(Number(params[0]), false),
  }
}

/** The server's logs, as GET /v1/admin/logs lists them: the main log first, then the Indexer's */
export const logInfos: A['LogInfo'][] = [
  { name: 'DMS', label: 'Main Log', size: 31_457_280, modified: '2026-11-19T10:41:07Z' },
  { name: 'IndexerTask', label: 'Indexer', size: 524_288, modified: '2026-11-19T10:41:07Z' },
]

/** An entry of a log, at the position given: the Indexer's last run, unless `over` says otherwise */
export function logLine(position: string, over: Partial<A['LogLine']> = {}): A['LogLine'] {
  return {
    position,
    time: '2026-11-19T10:41:07Z',
    level: 'INFO',
    source: 'com.logicaldoc.core.searchengine.IndexerTask:212',
    message: 'Finished indexing 5 documents in 4.2 s',
    continuation: [],
    ...over,
  }
}

/** The main log's last entries, oldest first, as the mockup draws them (newest at the top there); fakeLog gives the positions */
export function logEntries(): A['LogLine'][] {
  return [
    logLine('', { time: '2026-11-19T10:24:58Z', level: 'WARN', source: 'com.logicaldoc.core.stats.StatsCollector:118', message: 'Statistics took longer than expected: 42 s' }),
    logLine('', { time: '2026-11-19T10:28:12Z', source: 'com.logicaldoc.healdan.api.ApiTokens:77', message: 'Device token issued to treeve for MacBook' }),
    logLine('', { time: '2026-11-19T10:30:00Z', source: 'com.logicaldoc.core.task.TaskScheduler:54', message: 'Task IndexOptimizer started' }),
    logLine('', { time: '2026-11-19T10:36:10Z', source: 'com.logicaldoc.healdan.storage.Mover:301', message: 'Moved 18 files to Wasabi EU' }),
    logLine('', {
      time: '2026-11-19T10:37:44Z',
      level: 'ERROR',
      source: 'com.logicaldoc.healdan.storage.Mover:322',
      message: 'Upload to Wasabi EU failed: connection reset, will retry in 5 minutes',
      continuation: ['java.net.SocketException: Connection reset', '\tat java.base/sun.nio.ch.NioSocketImpl.implRead(NioSocketImpl.java:318)'],
    }),
    logLine('', { time: '2026-11-19T10:39:30Z', source: 'com.logicaldoc.core.security.SessionManager:140', message: 'Session opened for pnair' }),
    logLine('', { time: '2026-11-19T10:40:51Z', level: 'WARN', source: 'com.logicaldoc.healdan.signin.HealdanThrottle:90', message: 'Sign-in failed for username jclarke (2 of 5)' }),
    logLine('', { time: '2026-11-19T10:41:02Z', level: 'ERROR', source: 'com.logicaldoc.healdan.preview.PreviewWorker:366', message: 'Preview conversion timed out after 180 seconds, doc 10412 version 3' }),
    logLine('', {}),
  ]
}

/** What GET /v1/admin/logs/{name}/lines keeps of an entry: its level at `level` or above, and `q` in its text, in any case */
export function logKeeps(entry: A['LogLine'], query?: Record<string, unknown>): boolean {
  const levels: Record<string, readonly string[]> = { warn: ['WARN', 'ERROR', 'FATAL'], error: ['ERROR', 'FATAL'] }
  const level = String(query?.level ?? 'all')
  if (level !== 'all' && (entry.level === null || !levels[level]?.includes(entry.level))) return false
  const q = String(query?.q ?? '').trim().toLowerCase()
  return q === '' || [entry.message, entry.source ?? '', ...entry.continuation].some((text) => text.toLowerCase().includes(q))
}

/**
 * A log for GET /v1/admin/logs/{name}/lines over `entries`, which a test may add to as the log grows. Each entry's
 * position is `p<its index>`, whatever it was given, and so are `before` and `after`: before `p4` are entries 0 to 3, and
 * after it entries 4 on. Each read looks through at most `scan` entries, as the server looks through at most 5 MiB, so a
 * level or words can make an answer short, or empty, before the start; it keeps 200 at most, as the server does: going
 * back, the last 200 it found, and going forward the first 200, ending at the entry after them.
 */
export function fakeLog(entries: A['LogLine'][], scan = 200) {
  const at = (index: number) => `p${index}`
  const indexOf = (position: unknown) => Number(String(position).slice(1))
  return ({ query }: { query?: Record<string, unknown> }): A['LogLines'] => {
    const all = entries.map((entry, index) => ({ ...entry, position: at(index) }))
    const kept = (from: number, to: number) => all.slice(from, to).filter((entry) => logKeeps(entry, query)).slice(-200)
    if (query?.after !== undefined) {
      const from = Math.min(indexOf(query.after), all.length)
      const found = all.slice(from, Math.min(all.length, from + scan)).filter((entry) => logKeeps(entry, query))
      // Past 200, the read ends at the entry after the 200th kept
      const to = found.length > 200 ? indexOf(found[200]!.position) : Math.min(all.length, from + scan)
      return { lines: found.slice(0, 200), before: at(from), after: at(to), atStart: false, atEnd: to === all.length, rolled: false }
    }
    const to = query?.before === undefined ? all.length : Math.min(indexOf(query.before), all.length)
    const from = Math.max(0, to - scan)
    return { lines: kept(from, to), before: at(from), after: at(to), atStart: from === 0, atEnd: to === all.length, rolled: false }
  }
}

/** The Server Log's routes, to spread over the defaults: the logs, and each one's entries, over `main` for the main log */
export function logRoutes(main: A['LogLine'][] = logEntries()): Routes {
  const indexer = [logLine('', { message: 'Indexing started' }), logLine('', {})]
  const logs: Record<string, ReturnType<typeof fakeLog>> = { DMS: fakeLog(main), IndexerTask: fakeLog(indexer) }
  return {
    'GET /v1/admin/logs': logInfos,
    'GET /v1/admin/logs/*/lines': ({ params, query }: { params: string[]; query?: Record<string, unknown> }) => logs[params[0]!]?.({ query }) ?? notFound(),
  }
}

// Monitoring: the Audit Trail and Activity

/** A row of the Audit Trail: Tom Reeve downloading # 10388 of 1001.1 at 10:33 on 19 November 2026, unless `over` says otherwise */
export function auditRow(over: Partial<A['AuditRow']> = {}): A['AuditRow'] {
  return {
    id: 'd501',
    when: '2026-11-19T10:33:00Z',
    who: 'Tom Reeve',
    kind: 'document',
    event: 'event.downloaded',
    what: 'Downloaded',
    detail: '# 10388, Skeleton Argument.docx, version 1.4',
    from: '192.0.2.12',
    docNumber: 10388,
    folderId: 115,
    changes: null,
    ...over,
  }
}

/**
 * The morning of 19 November 2026, newest first: a row of each kind, a secret changed, and as the server answers them, a
 * failed sign-in for a username no account has (no one named, its typed text not answered), a change an administrator
 * made to another's account (Who is who made it, the Detail the account), and one whose row names no author
 */
export const auditRows: A['AuditRow'][] = [
  auditRow({
    id: 'a41',
    when: '2026-11-19T10:44:07.120Z',
    who: 'Sam Okafor',
    kind: 'administration',
    event: 'backend.changed',
    what: 'Changed a storage backend',
    detail: 'Wasabi EU',
    from: '192.0.2.9',
    docNumber: null,
    folderId: null,
    changes: { name: { before: 'Wasabi', after: 'Wasabi EU' }, secret: { secret: true } },
  }),
  auditRow({ id: 'u17', when: '2026-11-19T10:39:00Z', who: 'Priya Nair', kind: 'sign-in', event: 'event.user.login', what: 'Signed in', detail: 'Password, Safari on a Mac', from: '192.0.2.17', docNumber: null, folderId: null }),
  auditRow(),
  auditRow({
    id: 'f88',
    when: '2026-11-19T10:05:00Z',
    who: 'Helen Ashcombe',
    kind: 'folder',
    event: 'event.folder.permission',
    what: 'Changed permissions',
    detail: '/Client Work/1001.1 Charter Dispute – MV Northern Star/Privileged',
    from: '192.0.2.11',
    docNumber: null,
    folderId: 115,
  }),
  auditRow({
    id: 'a40',
    when: '2026-11-19T09:58:00Z',
    who: 'Sam Okafor',
    kind: 'administration',
    event: 'client.renamed',
    what: 'Renamed a client',
    detail: '1001 – Saltmarsh Shipping Ltd',
    from: '192.0.2.9',
    docNumber: null,
    folderId: null,
    changes: { description: { before: null, after: 'Shipping and charters' }, name: { before: 'Saltmarsh Shipping', after: 'Saltmarsh Shipping Ltd' } },
  }),
  auditRow({ id: 'u16', when: '2026-11-19T09:47:00Z', who: null, kind: 'sign-in', event: 'event.user.login.failed', what: 'Failed to sign in', detail: 'Unknown username', from: '192.0.2.21', docNumber: null, folderId: null }),
  auditRow({
    id: 'u15',
    when: '2026-11-19T09:12:00Z',
    who: 'Sam Okafor',
    kind: 'user',
    event: 'event.user.passwordchanged',
    what: 'Password changed',
    detail: 'Jamie Clarke (jclarke): By sokafor in the Admin app',
    from: '192.0.2.9',
    docNumber: null,
    folderId: null,
  }),
  auditRow({ id: 'u14', when: '2026-11-19T08:50:00Z', who: null, kind: 'user', event: 'event.user.disabled', what: 'Account disabled', detail: 'Ben Hollis (bhollis)', from: null, docNumber: null, folderId: null }),
]

/**
 * A page of the Audit Trail: its rows, where the next one starts (none, the end, unless given), and how far back its
 * reading reached, null exactly at the end, as the server says, and otherwise 1 November 2026 unless given
 */
export function auditPage(items: A['AuditRow'][], nextCursor: string | null = null, readBackTo = '2026-11-01T00:00:00Z'): A['AuditPage'] {
  return { items, nextCursor, readBackTo: nextCursor === null ? null : readBackTo }
}

/** GET /v1/admin/audit over `rows`: those of the kinds asked for (every kind without), in one page, the end */
export function auditTrail(query?: Record<string, unknown>, rows: A['AuditRow'][] = auditRows): A['AuditPage'] {
  const kinds = query?.kind === undefined ? undefined : ([] as unknown[]).concat(query.kind)
  return auditPage(rows.filter((row) => kinds === undefined || kinds.includes(row.kind)))
}

/** GET /v1/admin/matters as a search: the matters whose number starts with `q`, or whose name or client's name holds it, in any case */
export function auditMatters(query?: Record<string, unknown>): A['MatterList'] {
  const q = String(query?.q ?? '').trim().toLowerCase()
  const items = matterRows.filter((row) => row.number.startsWith(q) || row.name.toLowerCase().includes(q) || row.client.name.toLowerCase().includes(q))
  const limit = Number(query?.limit ?? 100)
  return matterList(items.slice(0, limit), { limit, total: items.length })
}

/** What the Audit Trail and the Activity tabs read, to spread over the defaults: the rows, the people and the matters */
export function auditRoutes(): Routes {
  return {
    ...peopleRoutes(),
    'GET /v1/admin/audit': ({ query }: { query?: Record<string, unknown> }) => auditTrail(query),
    'GET /v1/admin/matters': ({ query }: { query?: Record<string, unknown> }) => auditMatters(query),
    'GET /v1/admin/matters/*': ({ params }: { params: string[] }) => {
      const row = matterRows.find((one) => one.id === Number(params[0]))
      return row ? matterPage(row) : notFound()
    },
  }
}

// Monitoring: Background Jobs

/**
 * A job as GET /v1/admin/jobs gives it: LogicalDOC's Indexer, enabled and idle, its last run a success, every 30 minutes,
 * unless `over` says otherwise
 */
export function jobOf(over: Partial<A['Job']> = {}): A['Job'] {
  return {
    name: 'IndexerTask',
    label: 'Indexer',
    logicalDocTask: true,
    enabled: true,
    running: false,
    lastStart: '2026-11-19T10:30:00Z',
    lastEnd: '2026-11-19T10:30:42Z',
    result: 'succeeded',
    error: null,
    nextRun: '2026-11-19T11:00:00Z',
    overdue: false,
    canRun: true,
    canStop: false,
    switchable: true,
    schedule: { mode: 'simple', intervalSeconds: 1800, cron: null },
    ...over,
  }
}

/** One of Healdan's jobs, `name` and `label` given: switched on, idle, never switched off or rescheduled here */
export function jobOfHealdan(name: string, label: string, over: Partial<A['Job']> = {}): A['Job'] {
  return jobOf({ name, label, logicalDocTask: false, switchable: false, schedule: null, canRun: false, ...over })
}

/**
 * Every job, Healdan's eight in their order then LogicalDOC's six: none running, the Storage Retests' last run failed,
 * the Storage Nightly Check not run yet, the Statistics Collector overdue and the Tags Processor disabled
 */
export const jobRows: A['Job'][] = [
  jobOfHealdan('preview', 'Previews', { lastStart: '2026-11-19T10:41:00Z', lastEnd: '2026-11-19T10:41:02Z', nextRun: '2026-11-19T10:41:07Z' }),
  jobOfHealdan('email-index', 'Email Index', { result: 'done', lastStart: '2026-11-13T08:00:00Z', lastEnd: '2026-11-13T08:04:00Z', nextRun: null }),
  jobOfHealdan('trash-destroyer', 'Trash Destroyer', { canRun: true, lastStart: '2026-11-19T02:47:00Z', lastEnd: '2026-11-19T02:47:05Z', nextRun: '2026-11-20T02:47:00Z' }),
  jobOfHealdan('storage-mover', 'Storage Mover', { canRun: true, lastStart: '2026-11-19T10:36:00Z', lastEnd: '2026-11-19T10:36:12Z', nextRun: '2026-11-19T10:41:00Z' }),
  jobOfHealdan('storage-retest', 'Storage Retests', {
    canRun: true,
    result: 'failed',
    error: "Wasabi EU can't be reached: connect timed out",
    lastStart: '2026-11-19T10:40:00Z',
    lastEnd: '2026-11-19T10:40:30Z',
    nextRun: '2026-11-19T10:45:00Z',
  }),
  jobOfHealdan('storage-purge', 'Storage Purge', { canRun: true, lastStart: '2026-11-19T04:00:00Z', lastEnd: '2026-11-19T04:00:09Z', nextRun: '2026-11-20T04:00:00Z' }),
  jobOfHealdan('storage-alert', 'Storage Alert', { lastStart: '2026-11-19T10:00:00Z', lastEnd: '2026-11-19T10:00:01Z', nextRun: '2026-11-19T11:00:00Z' }),
  jobOfHealdan('storage-nightly-check', 'Storage Nightly Check', { canRun: true, result: null, lastStart: null, lastEnd: null, nextRun: '2026-11-20T03:15:00Z' }),
  jobOf(),
  jobOf({
    name: 'IndexOptimizer',
    label: 'Index Optimizer',
    lastStart: '2026-11-19T02:30:00Z',
    lastEnd: '2026-11-19T02:31:10Z',
    nextRun: '2026-11-20T02:30:00Z',
    schedule: { mode: 'cron', intervalSeconds: null, cron: '0 30 2 * * ?' },
  }),
  jobOf({
    name: 'StatsCollector',
    label: 'Statistics Collector',
    lastStart: '2026-11-15T04:00:00Z',
    lastEnd: '2026-11-15T04:00:42Z',
    nextRun: '2026-11-16T04:00:00Z',
    overdue: true,
    schedule: { mode: 'simple', intervalSeconds: 86_400, cron: null },
  }),
  jobOf({ name: 'TagsProcessor', label: 'Tags Processor', enabled: false, canRun: false, result: null, lastStart: null, lastEnd: null, nextRun: null, schedule: { mode: 'simple', intervalSeconds: 3600, cron: null } }),
  jobOf({
    name: 'DigestProcessor',
    label: 'Digest Processor',
    lastStart: '2026-11-19T07:00:00Z',
    lastEnd: '2026-11-19T07:00:03Z',
    nextRun: '2026-11-20T07:00:00Z',
    schedule: { mode: 'cron', intervalSeconds: null, cron: '0 0 7 ? * MON-FRI' },
  }),
  jobOf({ name: 'PathCalculator', label: 'Paths Calculator', lastStart: '2026-11-19T10:40:00Z', lastEnd: '2026-11-19T10:40:01Z', nextRun: '2026-11-19T10:45:00Z', schedule: { mode: 'simple', intervalSeconds: 300, cron: null } }),
]

/** A `422 validation` that names one member of a schedule's change, as PUT /v1/admin/jobs/{name}/schedule answers it */
export function jobFieldProblem(field: string, code: string): ApiError {
  return new ApiError(422, 'validation', 'Some details need fixing.', { status: 422, code: 'validation', detail: 'Some details need fixing.', fields: [{ field, code, detail: 'A server sentence.' }] })
}

/**
 * What PUT /v1/admin/jobs/{name}/schedule refuses of a change, as the server checks it, in its order: the mode, then the
 * member for it (an interval from 1 to 31,536,000 seconds, or a cron expression that isn't blank, which the server gives
 * to Quartz and the fake asks only to have six fields or seven), then the other mode's member, sent with a value
 */
export function jobScheduleProblem(body: unknown): ApiError | undefined {
  const change = (body ?? {}) as Record<string, unknown>
  if (change.mode === undefined || change.mode === null) return jobFieldProblem('mode', 'required')
  if (change.mode === 'simple') {
    const seconds = change.intervalSeconds
    if (seconds === undefined || seconds === null) return jobFieldProblem('intervalSeconds', 'required')
    if (typeof seconds !== 'number' || !Number.isInteger(seconds) || seconds < 1 || seconds > 31_536_000) return jobFieldProblem('intervalSeconds', 'out-of-range')
    if (change.cron !== undefined && change.cron !== null) return jobFieldProblem('cron', 'invalid')
    return undefined
  }
  if (change.mode === 'cron') {
    const cron = typeof change.cron === 'string' ? change.cron.trim() : ''
    if (cron === '') return jobFieldProblem('cron', 'required')
    const fields = cron.split(/\s+/).length
    if (fields < 6 || fields > 7) return jobFieldProblem('cron', 'invalid')
    if (change.intervalSeconds !== undefined && change.intervalSeconds !== null) return jobFieldProblem('intervalSeconds', 'invalid')
    return undefined
  }
  return jobFieldProblem('mode', 'invalid')
}

/** When a run asked for now begins, in the fakes' answers */
export const JOB_RUN_STARTED = '2026-11-19T10:42:00Z'

export interface JobRoutesOptions {
  /**
   * The run begins off the request's thread, so Run Now's answer is the job as it was, and only a later read shows it
   * running; by default the answer shows it running already
   */
  runAnswersBefore?: boolean
}

/**
 * Background Jobs' routes, to spread over the defaults: the jobs, over copies of `rows`, and their actions, which change
 * those copies as the server would and answer the job, or refuse what it doesn't allow now with a `409 refused`, as the
 * server does; a name the list doesn't have is `404`
 */
export function jobRoutes(rows: A['Job'][] = jobRows, options: JobRoutesOptions = {}): Routes {
  const jobs = new Map(rows.map((job) => [job.name, { ...job }]))
  const refused = (sentence: string) => problem(409, 'refused', sentence)
  function change(job: A['Job'], next: Partial<A['Job']>): A['Job'] {
    const changed = { ...job, ...next }
    jobs.set(job.name, changed)
    return { ...changed }
  }
  // The job the path names, decided on, or a 404
  function act(params: string[], decide: (job: A['Job']) => A['Job'] | ApiError): A['Job'] | ApiError {
    const job = jobs.get(decodeURIComponent(params[0] ?? ''))
    return job === undefined ? notFound() : decide(job)
  }
  const unswitchable = (job: A['Job']) => refused(`${job.label} isn't switched on or off here.`)
  return {
    'GET /v1/admin/jobs': () => [...jobs.values()].map((job) => ({ ...job })),
    'POST /v1/admin/jobs/*/run': ({ params }: { params: string[] }) =>
      act(params, (job) => {
        if (!job.canRun) return refused(`${job.label} can't run now.`)
        const before = { ...job }
        const begun = change(job, {
          running: true,
          canRun: false,
          canStop: job.logicalDocTask,
          lastStart: JOB_RUN_STARTED,
          ...(job.logicalDocTask ? { lastEnd: null, result: null, error: null } : {}),
        })
        return options.runAnswersBefore ? before : begun
      }),
    'POST /v1/admin/jobs/*/stop': ({ params }: { params: string[] }) => act(params, (job) => (job.canStop ? change(job, { canStop: false }) : refused(`${job.label} isn't running.`))),
    'POST /v1/admin/jobs/*/enable': ({ params }: { params: string[] }) =>
      act(params, (job) => (job.switchable ? change(job, { enabled: true, canRun: !job.running, nextRun: job.nextRun ?? '2026-11-19T11:00:00Z' }) : unswitchable(job))),
    'POST /v1/admin/jobs/*/disable': ({ params }: { params: string[] }) =>
      act(params, (job) => (job.switchable ? change(job, { enabled: false, canRun: false, nextRun: null, overdue: false }) : unswitchable(job))),
    'PUT /v1/admin/jobs/*/schedule': ({ params, body }: { params: string[]; body?: unknown }) =>
      act(params, (job) => {
        const invalid = jobScheduleProblem(body)
        if (invalid !== undefined) return invalid
        if (job.schedule === null) return refused(`${job.label} has no schedule here.`)
        const sent = body as A['JobScheduleChange']
        return change(job, {
          schedule: sent.mode === 'simple' ? { mode: 'simple', intervalSeconds: sent.intervalSeconds ?? null, cron: null } : { mode: 'cron', intervalSeconds: null, cron: (sent.cron ?? '').trim() },
        })
      }),
  }
}

// Search Index (Phase 6): the index's counts, the documents that couldn't be indexed and those waiting, the index's
// events, and the reindexes, as the Search Index screen reads them. Every name here starts searchIndex.

/** A document as the Search Index names it: # 10412, Witness Statement.pdf, of 1001.1, unless `over` says otherwise */
export function searchIndexDocument(over: Partial<A['IndexDocument']> = {}): A['IndexDocument'] {
  return { id: 5412, docNumber: 10412, fileName: 'Witness Statement.pdf', matter: { id: 11, number: '1001.1', name: 'Charter Dispute' }, ...over }
}

/** The two documents that couldn't be indexed, the latest error first, as the mockup has them */
export const searchIndexFailures: A['IndexFailure'][] = [
  { document: searchIndexDocument(), reason: 'The file is encrypted', when: '2026-11-19T08:31:00Z' },
  {
    document: searchIndexDocument({ id: 5398, docNumber: 10398, fileName: 'Heads of Terms.pdf', matter: { id: 31, number: '1003.1', name: 'Series A Investment' } }),
    reason: 'The file is encrypted',
    when: '2026-11-19T08:30:00Z',
  },
]

/** Five documents waiting for the Indexer, in the order they were filed */
export const searchIndexWaiting: A['IndexWaiting'][] = [10420, 10421, 10422, 10423, 10424].map((docNumber, i) => ({
  document: searchIndexDocument({ id: 5420 + i, docNumber, fileName: `Exhibit ${i + 1}.pdf` }),
  lastChanged: `2026-11-19T10:${String(35 + i)}:00Z`,
}))

/** GET /v1/admin/search-index: 1,277 indexed, 5 waiting and 2 that couldn't be, the Indexer idle and free to run */
export function searchIndexOf(over: Partial<A['SearchIndex']> = {}): A['SearchIndex'] {
  return {
    indexed: 1277,
    waiting: 5,
    failed: 2,
    entries: 1290,
    bytes: 412 * MB,
    health: { status: 'attention', detail: '5 waiting, 2 failed' },
    indexer: { job: jobOf(), runRefusal: null },
    ...over,
  }
}

/** A reindex: Sam's of matter 1001.1, running, 212 of 412 tried, about 6 minutes left, unless `over` says otherwise */
export function searchIndexJob(over: Partial<A['ReindexJob']> = {}): A['ReindexJob'] {
  return {
    id: 7,
    kind: 'matter',
    targetId: 11,
    name: 'Matter 1001.1 Charter Dispute',
    startedBy: sam,
    state: 'running',
    cancelling: false,
    emptyFirst: false,
    total: 412,
    done: 208,
    failed: 2,
    skipped: 2,
    unreachable: 0,
    leftOut: 3,
    created: '2026-11-19T10:42:00Z',
    started: '2026-11-19T10:42:00Z',
    ended: null,
    stalledSince: null,
    failingSince: null,
    estimateSecondsLeft: 360,
    ...over,
  }
}

/** A reindex waiting its turn: Helen's of the Store Client Work */
export const searchIndexWaitingJob = searchIndexJob({
  id: 8,
  kind: 'store',
  targetId: 4,
  name: 'Store Client Work',
  startedBy: helen,
  state: 'waiting',
  total: 1004,
  done: 0,
  failed: 0,
  skipped: 0,
  created: '2026-11-19T10:50:00Z',
  started: null,
  estimateSecondsLeft: 1757,
})

/** A reindex that ended: Tom's of three documents, last week */
export const searchIndexDoneJob = searchIndexJob({
  id: 5,
  kind: 'documents',
  targetId: null,
  name: '3 Documents',
  startedBy: tom,
  state: 'done',
  total: 3,
  done: 2,
  failed: 1,
  skipped: 0,
  leftOut: 0,
  created: '2026-11-12T09:00:00Z',
  started: '2026-11-12T09:00:00Z',
  ended: '2026-11-12T09:00:06Z',
  estimateSecondsLeft: null,
})

/** An event of the index: # 10412 couldn't be indexed by the Indexer at 08:31, unless `over` says otherwise */
export function searchIndexEvent(over: Partial<A['IndexEvent']> = {}): A['IndexEvent'] {
  return { id: 9001, when: '2026-11-19T08:31:00Z', event: 'failed', reason: 'The file is encrypted', who: 'System', document: searchIndexDocument(), ...over }
}

export const searchIndexEvents: A['IndexEvent'][] = [
  searchIndexEvent({ id: 9003, when: '2026-11-19T10:40:00Z', event: 'indexed', reason: null, who: 'Sam Okafor', document: searchIndexDocument({ id: 5401, docNumber: 10401, fileName: 'Charter Party.pdf' }) }),
  searchIndexEvent({ id: 9002, when: '2026-11-19T08:32:00Z', reason: 'The file is encrypted', document: searchIndexFailures[1]!.document }),
  searchIndexEvent(),
]

/** A page of the index's events: its rows, where the next one starts (none, the end, unless given), and how far back it read */
export function searchIndexEventPage(items: A['IndexEvent'][], nextCursor: string | null = null, readBackTo = '2026-11-01T00:00:00Z'): A['IndexHistoryPage'] {
  return { items, nextCursor, readBackTo: nextCursor === null ? null : readBackTo }
}

/** A page by offset of `rows`, as the Search Index's paged routes answer, its `offset` and `limit` from the query */
function searchIndexPage<T>(rows: T[], query?: Record<string, unknown>) {
  const offset = Number(query?.offset ?? 0)
  const limit = Number(query?.limit ?? 100)
  return { items: rows.slice(offset, offset + limit), offset, limit, total: rows.length }
}

export interface SearchIndexRoutesOptions {
  index?: A['SearchIndex']
  failures?: A['IndexFailure'][]
  waiting?: A['IndexWaiting'][]
  /** Every reindex, the newest first; those waiting or running are the active ones */
  jobs?: A['ReindexJob'][]
  events?: A['IndexEvent'][]
  /** How Try Again goes for a doc #: indexed by default */
  retry?: (docNumber: number) => A['IndexRetry'] | ApiError
}

/**
 * The Search Index's routes, to spread over the defaults, over copies of what `options` give (the mockup's, by default):
 * Try Again takes an indexed document out of the failures, as the server would, and Skip takes it out too, each answering
 * a document that isn't there any more `409 refused` and a doc # none has `404`; Run Indexer Now runs the Indexer, or is
 * refused with its reason; Cancel cancels a waiting reindex at once and asks a running one to stop. The counts follow.
 */
export function searchIndexRoutes(options: SearchIndexRoutesOptions = {}) {
  let index = options.index ?? searchIndexOf()
  let failures = [...(options.failures ?? searchIndexFailures)]
  const waiting = [...(options.waiting ?? searchIndexWaiting)]
  const jobs = new Map((options.jobs ?? [searchIndexWaitingJob, searchIndexJob(), searchIndexDoneJob]).map((job) => [job.id, { ...job }]))
  const events = options.events ?? searchIndexEvents
  const refused = (sentence: string) => problem(409, 'refused', sentence)
  const active = () => [...jobs.values()].filter((job) => job.state === 'waiting' || job.state === 'running')
  // The failure the path's doc # names, decided on, or the server's refusals
  function failed(params: string[], decide: (failure: A['IndexFailure']) => unknown): unknown {
    const docNumber = Number(params[0])
    const failure = failures.find((one) => one.document.docNumber === docNumber)
    if (failure !== undefined) return decide(failure)
    const known = [...searchIndexFailures, ...waiting].some((one) => one.document.docNumber === docNumber)
    return known ? refused('This document is no longer waiting after an error.') : notFound()
  }
  function without(failure: A['IndexFailure']) {
    failures = failures.filter((one) => one !== failure)
    index = { ...index, failed: failures.length }
  }
  const routes = {
    'GET /v1/admin/search-index': () => index,
    'GET /v1/admin/search-index/failed': ({ query }: { query?: Record<string, unknown> }) => searchIndexPage(failures, query) satisfies A['IndexFailureList'],
    'GET /v1/admin/search-index/waiting': ({ query }: { query?: Record<string, unknown> }) => searchIndexPage(waiting, query) satisfies A['IndexWaitingList'],
    'GET /v1/admin/search-index/history': () => searchIndexEventPage(events),
    'POST /v1/admin/search-index/run': () => {
      if (index.indexer.runRefusal !== null) return refused(index.indexer.runRefusal)
      const job = { ...index.indexer.job, running: true, canRun: false, canStop: true, lastStart: JOB_RUN_STARTED, lastEnd: null, result: null }
      index = { ...index, indexer: { job, runRefusal: 'This job is running already.' } }
      return index.indexer
    },
    'POST /v1/admin/search-index/failed/*/retry': ({ params }: { params: string[] }) =>
      failed(params, (failure) => {
        const outcome = options.retry?.(failure.document.docNumber ?? -1) ?? ({ outcome: 'indexed', reason: null } satisfies A['IndexRetry'])
        if (outcome instanceof ApiError) return outcome
        if (outcome.outcome === 'indexed') without(failure)
        else failures = failures.map((one) => (one === failure ? { ...one, reason: outcome.reason, when: '2026-11-19T10:45:00Z' } : one))
        return outcome
      }),
    'POST /v1/admin/search-index/failed/*/skip': ({ params }: { params: string[] }) =>
      failed(params, (failure) => {
        without(failure)
        return undefined
      }),
    'GET /v1/admin/reindex': ({ query }: { query?: Record<string, unknown> }) => {
      const rows = query?.active === 'true' ? active() : [...jobs.values()]
      return searchIndexPage(
        rows.sort((a, b) => b.id - a.id).map((job) => ({ ...job })),
        query,
      ) satisfies A['ReindexJobList']
    },
    'GET /v1/admin/reindex/*': ({ params }: { params: string[] }) => {
      const job = jobs.get(Number(params[0]))
      return job === undefined ? notFound() : { ...job }
    },
    'DELETE /v1/admin/reindex/*': ({ params }: { params: string[] }) => {
      const job = jobs.get(Number(params[0]))
      if (job === undefined) return notFound()
      if (job.state === 'done' || job.state === 'cancelled') return refused('This reindex has ended.')
      const next: A['ReindexJob'] = job.state === 'waiting' ? { ...job, state: 'cancelled', ended: '2026-11-19T10:51:00Z', estimateSecondsLeft: null } : { ...job, cancelling: true }
      jobs.set(job.id, next)
      return { ...next }
    },
  }
  return {
    routes,
    /** A reindex, as the server now has it, as when it runs on or ends meanwhile */
    setJob: (job: A['ReindexJob']) => void jobs.set(job.id, { ...job }),
    setIndex: (next: A['SearchIndex']) => void (index = next),
  }
}

// Reindex dialog (Phase 6): Recent Matters, the documents its doc #s name, what each choice would cover, and the reindex
// a start answers, each from the fixtures above, so the counts agree with the Matters list's and the Stores'.

/** A matter as the public Recent Matters lists it, from its row: its number within its client, and its client's */
function reindexRecentOf(row: A['MatterRow']): S['MatterView'] {
  const [, within] = row.number.split('.')
  return {
    type: 'matter',
    id: row.id,
    number: Number(within),
    display: `${row.number} - ${row.name}`,
    name: row.name,
    description: null,
    client: { id: row.client.id, number: row.client.number, name: row.client.name },
    store: { id: row.store.id, name: row.store.name },
    folderId: row.folderId,
    lastChange: '2026-11-19T09:00:00Z',
  }
}

const reindexRow = (id: number) => matterRows.find((row) => row.id === id)!

/** GET /v1/matters/recent: 1003.1, 1001.1 and 1002.2, the newest first */
export const reindexRecent: S['MatterView'][] = [31, 11, 22].map((id) => reindexRecentOf(reindexRow(id)))

const reindexMatterOf = (row: A['MatterRow']): A['MatterBrief'] => ({ id: row.id, number: row.number, name: row.name })

/** The documents the doc #s name, by their doc #: 10377 is set to Skip, so a reindex leaves it out */
export const reindexDocuments: Record<number, { document: A['IndexDocument']; status: 'found' | 'left-out' }> = {
  10412: { document: { id: 5012, docNumber: 10412, fileName: 'Witness Statement.pdf', matter: reindexMatterOf(reindexRow(11)) }, status: 'found' },
  10398: { document: { id: 4998, docNumber: 10398, fileName: 'Heads of Terms.pdf', matter: reindexMatterOf(reindexRow(31)) }, status: 'found' },
  10388: { document: { id: 4988, docNumber: 10388, fileName: 'Skeleton Argument.docx', matter: reindexMatterOf(reindexRow(11)) }, status: 'found' },
  10377: { document: { id: 4977, docNumber: 10377, fileName: 'Old Draft.docx', matter: reindexMatterOf(reindexRow(11)) }, status: 'left-out' },
}

/** The documents in each folder of 1001.1's workspace (charterTree), every folder inside it counted: none in a search folder */
export const reindexFolderCounts: Record<number, number> = { 111: 96, 112: 44, 113: 171, 114: 38, 115: 15, 116: 4 }

/** Every document, as Everything counts them: the Dashboard's total */
export const REINDEX_EVERYTHING = 1284

/** What POST /v1/admin/reindex/preview answers for a scope, at P6-R9's 1.75 seconds a document, no reindex having run */
export function reindexPreviewOf(scope: A['ReindexScope']): A['ReindexPreview'] | ApiError {
  const answer = (name: string, count: number, skipped = 0, documents: A['ReindexMatch'][] | null = null): A['ReindexPreview'] => ({
    name,
    count,
    skipped,
    estimateSeconds: count === 0 ? null : Math.ceil(count * 1.75),
    documents,
    refusal: count === 0 ? "There's nothing here to reindex." : null,
  })
  switch (scope.kind) {
    case 'documents': {
      const given = [...new Set(scope.docNumbers ?? [])]
      const matches = given.map((docNumber): A['ReindexMatch'] => {
        const known = reindexDocuments[docNumber]
        return known ? { docNumber, status: known.status, document: known.document } : { docNumber, status: 'not-found', document: null }
      })
      const found = matches.filter((one) => one.status === 'found').length
      const leftOut = matches.filter((one) => one.status === 'left-out').length
      const named = found + leftOut
      return answer(named === 1 ? '1 Document' : `${named} Documents`, found, leftOut, matches)
    }
    case 'matter': {
      const row = matterRows.find((one) => one.id === scope.targetId)
      return row ? answer(`Matter ${row.number} ${row.name}`, row.documents ?? 0, 2) : problem(404, 'not-found')
    }
    case 'folder': {
      const node = scope.targetId === null || scope.targetId === undefined ? undefined : placed(charterTree).find((one) => one.node.id === scope.targetId)?.node
      if (node === undefined) return problem(404, 'not-found')
      const row = reindexRow(11)
      return answer(`Folder ${node.name} in ${row.number} ${row.name}`, reindexFolderCounts[node.id] ?? 0)
    }
    case 'store': {
      const store = stores.find((one) => one.id === scope.targetId)
      return store ? answer(`Store ${store.name}`, store.documents ?? 0) : problem(404, 'not-found')
    }
    case 'everything':
      return answer('Everything', REINDEX_EVERYTHING, 12)
  }
}

/** A reindex as POST /v1/admin/reindex answers it: just started by Alex, waiting for its turn, unless `over` says otherwise */
export function reindexJobOf(over: Partial<A['ReindexJob']> = {}): A['ReindexJob'] {
  return {
    id: 7,
    kind: 'matter',
    targetId: 11,
    name: 'Matter 1001.1 Charter Dispute – MV Northern Star',
    startedBy: { id: me.id, username: me.username, name: me.name },
    state: 'waiting',
    cancelling: false,
    emptyFirst: false,
    total: 412,
    done: 0,
    failed: 0,
    skipped: 0,
    unreachable: 0,
    leftOut: 2,
    created: '2026-11-23T10:00:00Z',
    started: null,
    ended: null,
    stalledSince: null,
    failingSince: null,
    estimateSecondsLeft: 721,
    ...over,
  }
}

/**
 * What the Reindex dialog reads, to spread over the defaults: Recent Matters, the Matters list searched by number, name or
 * client, each matter's page, 1001.1's workspace, the preview of each choice, and a start that answers the reindex of what
 * was sent.
 */
export function reindexRoutes(): Routes {
  return {
    'GET /v1/matters/recent': { items: reindexRecent, offset: 0, limit: 20, total: reindexRecent.length } satisfies S['MatterPage'],
    'GET /v1/admin/matters': ({ query }: { query?: Record<string, unknown> }) => {
      const q = String(query?.q ?? '').trim().toLowerCase()
      const items = matterRows.filter((row) => row.number.startsWith(q) || row.name.toLowerCase().includes(q) || row.client.name.toLowerCase().includes(q))
      return matterList(items, { limit: Number(query?.limit ?? 100) })
    },
    ...Object.fromEntries(matterRows.map((row) => [`GET /v1/admin/matters/${row.id}`, matterPage(row)])),
    'GET /v1/admin/workspaces/110/tree': charterTree,
    'POST /v1/admin/reindex/preview': ({ body }: { body?: unknown }) => reindexPreviewOf(body as A['ReindexScope']),
    'POST /v1/admin/reindex': ({ body }: { body?: unknown }) => {
      const scope = body as A['ReindexScope']
      const preview = reindexPreviewOf(scope)
      if (preview instanceof ApiError) return preview
      if (preview.refusal !== null) return problem(409, 'refused', preview.refusal)
      return reindexJobOf({ kind: scope.kind, targetId: scope.targetId ?? null, name: preview.name, emptyFirst: scope.emptyFirst ?? false, total: preview.count, leftOut: preview.skipped })
    },
  }
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
    ...storeRoutes(),
    ...storageRoutes(),
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
