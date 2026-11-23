// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { idFrom } from '@healdan/shell'

/** Where AdminAppServlet serves the app, the router's basepath. Only the root context is supported. */
export const ADMIN_BASE = '/admin'

/**
 * Every page's address under ADMIN_BASE (spec 6.3), as the router's paths and Links name them: `$id` is a page's id. No
 * segment is one CE's filters or login.jsp treat as their own (addresses.test.ts checks them, and every routed one).
 */
export const routes = {
  dashboard: '/',
  clients: '/clients',
  client: '/clients/$id',
  matters: '/matters',
  matter: '/matters/$id',
  templates: '/templates',
  template: '/templates/$id',
  stores: '/stores',
  store: '/stores/$id',
  backends: '/backends',
  backend: '/backends/$id',
  users: '/users',
  user: '/users/$id',
  groups: '/groups',
  group: '/groups/$id',
  errors: '/errors',
  serverLog: '/server-log',
  audit: '/audit',
  jobs: '/jobs',
  searchIndex: '/search-index',
} as const

/**
 * An id or a doc # in a search parameter, as the address gives it (as text) or as the router validated it before (as a
 * number), or undefined: @healdan/shell's idFrom reads it, digits only, no bigger than a safe integer.
 */
export function searchId(value: unknown): number | undefined {
  return idFrom(typeof value === 'number' ? String(value) : typeof value === 'string' ? value : undefined)
}

/** The Search Index's tabs (spec 8.9): Couldn't Be Indexed, the one it opens on, Waiting and History */
export type IndexTab = 'failed' | 'waiting' | 'history'

/** What a reindex covers, as the Reindex dialog's choices and `ReindexScope.kind` name it */
export type ReindexKind = 'documents' | 'folder' | 'matter' | 'store' | 'everything'

export const reindexKinds = ['documents', 'folder', 'matter', 'store', 'everything'] as const satisfies readonly ReindexKind[]

/**
 * What the Search Index's address holds (P6-R18): the tab open, when it isn't Couldn't Be Indexed; and the Reindex dialog,
 * open on a choice (`reindex`) with what it's chosen on: `id`, the matter's, the folder's or the Store's id, and `matter`,
 * the matter whose folders the Folder choice lists. The page reads the tab; the dialog reads the rest.
 */
export interface SearchIndexSearch {
  tab?: Exclude<IndexTab, 'failed'>
  reindex?: ReindexKind
  id?: number
  matter?: number
}

/**
 * The address's parameters the Search Index reads; anything else, or anything that isn't what it should be, is left out,
 * and Couldn't Be Indexed is the tab when it names none. The ids may come as numbers, as the router validated them before.
 */
export function searchIndexSearch(search: Record<string, unknown>): SearchIndexSearch {
  const tab = search.tab === 'waiting' || search.tab === 'history' ? search.tab : undefined
  return { tab, reindex: reindexKinds.find((kind) => kind === search.reindex), id: searchId(search.id), matter: searchId(search.matter) }
}

/**
 * The Search Index with the Reindex dialog open on a choice, with ADMIN_BASE, as a matter's page and the Workspace Editor
 * link to it (P6-R18): `/admin/search-index?reindex=matter&id=12`, and for a folder its matter too
 */
export function reindexHref(kind: ReindexKind, id?: number, matter?: number): string {
  const params = new URLSearchParams({ reindex: kind })
  if (id !== undefined) params.set('id', String(id))
  if (matter !== undefined) params.set('matter', String(matter))
  return `${ADMIN_BASE}${routes.searchIndex}?${params.toString()}`
}

/** A client's page, with ADMIN_BASE: the client's id, as one segment */
export function clientHref(id: number): string {
  return `${ADMIN_BASE}/clients/${encodeURIComponent(String(id))}`
}

/**
 * A matter's page, with ADMIN_BASE, as a link or the router's `href` takes it: the matter's id, never its folder's, as
 * one segment.
 */
export function matterHref(id: number): string {
  return `${ADMIN_BASE}/matters/${encodeURIComponent(String(id))}`
}

/** A Store's page, with ADMIN_BASE: the Store's id, which is its folder's, as one segment */
export function storeHref(id: number): string {
  return `${ADMIN_BASE}/stores/${encodeURIComponent(String(id))}`
}

/** A workspace template's page, with ADMIN_BASE: the template's id, which is its folder's, as one segment */
export function templateHref(id: number): string {
  return `${ADMIN_BASE}/templates/${encodeURIComponent(String(id))}`
}

/** A person's page, with ADMIN_BASE: the user's id, as one segment */
export function userHref(id: number): string {
  return `${ADMIN_BASE}/users/${encodeURIComponent(String(id))}`
}

/**
 * A group's id as an address or a search parameter gives it (as text, or as the number the router validated before), or
 * undefined: digits, with a minus first for one of LogicalDOC's own groups, whose ids are negative (`publisher` is -10000),
 * as a safe integer. @healdan/shell's idFrom, which the User app shares, takes no minus, as no other id the apps read is
 * negative, so a group's id is read with this one wherever the Admin app reads one.
 */
export function groupIdFrom(text: string | number | undefined): number | undefined {
  const value = typeof text === 'number' ? String(text) : text
  if (value === undefined || !/^-?\d{1,16}$/.test(value)) return undefined
  const id = Number(value)
  if (!Number.isSafeInteger(id)) return undefined
  // -0 is the group 0
  return id === 0 ? 0 : id
}

/** A group's page, with ADMIN_BASE: the group's id, as one segment, a negative one too */
export function groupHref(id: number): string {
  return `${ADMIN_BASE}/groups/${encodeURIComponent(String(id))}`
}

/** A folder in the User app, as Open in Healdan opens a matter's (spec 6.4): its folder's id, as one segment */
export function userAppFolderHref(folderId: number): string {
  return `/app/folders/${encodeURIComponent(String(folderId))}`
}

/** A document in the User app, by its # handle, as the Audit Trail's details open one: the doc #, as one segment */
export function userAppDocHref(docNumber: number): string {
  return `/app/doc/${encodeURIComponent(String(docNumber))}`
}

/** A link as the browser reads it against the page, when it's on this origin: a server's link to another is no page here */
function sameOrigin(link: string): URL | undefined {
  try {
    const url = new URL(link, window.location.href)
    return url.origin === window.location.origin ? url : undefined
  } catch {
    return undefined
  }
}

/** A link's path on this origin, without its query or hash; undefined for another origin's */
export function pathOf(link: string): string | undefined {
  return sameOrigin(link)?.pathname
}

/**
 * The address in this app a link names (ADMIN_BASE, or under it, on this origin) with its query and hash, as the router's
 * `href` takes it; undefined for any other: the Classic Interface, another of the server's pages or another origin.
 */
export function appAddressOf(link: string): string | undefined {
  const url = sameOrigin(link)
  if (url === undefined || (url.pathname !== ADMIN_BASE && !url.pathname.startsWith(`${ADMIN_BASE}/`))) return undefined
  return url.pathname + url.search + url.hash
}
