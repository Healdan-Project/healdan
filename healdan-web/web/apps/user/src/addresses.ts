// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

/** Where AppServlet serves the app, the router's basepath. Only the root context is supported in M6. */
export const APP_BASE = '/app'

/** A document's own page in the app, as Copy Link and Open in New Tab give it */
export function docHref(docNumber: number): string {
  return `${APP_BASE}/doc/${docNumber}`
}

/** A matter's page in the Admin app, which Manage in Admin opens: a plain link, as no Admin code is in this app */
export function adminMatterHref(matterId: number): string {
  return `/admin/matters/${matterId}`
}

/** A folder's page in the app, as Open in New Tab gives it */
export function folderHref(folderId: number): string {
  return `${APP_BASE}/folders/${folderId}`
}
