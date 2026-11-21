// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { ApiError, type adminComponents, type Api, type components } from '@healdan/api'
import { inOrder, levelOf, permissionsOf, type NamedLevel } from '../workspace/levels'

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
    figuresAt: null,
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

// A matter's page, its Workspace tab and the workspace templates (Task 20): matter 1001.1's workspace and the Litigation
// template's, who can use their folders, the values they set and the rules of their search folders.

/** A matter, for its page: 1001.1 unless `row` and `over` say otherwise */
export function matterPage(row: A['MatterRow'] = matterRow(), over: Partial<A['Matter']> = {}): A['Matter'] {
  return { ...row, description: 'Charter party dispute over the MV Northern Star.', indexFailed: 0, previewsGivenUp: 0, figuresAt: '2026-11-19T10:41:00Z', ...over }
}

export const wsEveryone: A['Group'] = { id: 2, name: 'Everyone', description: 'Everyone in the firm' }
export const wsGuest: A['Group'] = { id: 3, name: 'wsGuest', description: null }
export const wsPartners: A['Group'] = { id: 20, name: 'Partners', description: null }
export const wsTrainees: A['Group'] = { id: 21, name: 'Trainees', description: null }

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
