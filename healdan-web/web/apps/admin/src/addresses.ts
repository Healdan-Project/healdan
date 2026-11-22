// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

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

/** A folder in the User app, as Open in Healdan opens a matter's (spec 6.4): its folder's id, as one segment */
export function userAppFolderHref(folderId: number): string {
  return `/app/folders/${encodeURIComponent(String(folderId))}`
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
