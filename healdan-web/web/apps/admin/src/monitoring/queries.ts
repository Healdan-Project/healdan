// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { adminComponents, Api } from '@healdan/api'
import { useCursorList, type CursorListing, type CursorQuery } from '../lists/useCursorList'

type A = adminComponents['schemas']

/** How many rows of the Audit Trail a page asks for */
export const AUDIT_PAGE_SIZE = 100

/** The Audit Trail's filters, as its route takes them, but for the time zone, which every request sends itself */
export type AuditQuery = Omit<CursorQuery<'/v1/admin/audit'>, 'zone'>

/** The browser's IANA time zone, in which the server cuts the days of `from` and `to`, as the User app's lists send theirs (R90) */
export function browserZone(): string {
  return Intl.DateTimeFormat().resolvedOptions().timeZone
}

/**
 * The Audit Trail's rows for the filters, newest first, AUDIT_PAGE_SIZE at a time by cursor (useCursorList, P5-R22), each
 * request with the browser's time zone. The Audit Trail and the Activity tabs read it.
 */
export function useAuditList(query: AuditQuery): CursorListing<A['AuditRow']> {
  return useCursorList('/v1/admin/audit', { ...query, zone: browserZone() }, AUDIT_PAGE_SIZE)
}

/**
 * Export CSV's address: the export with the filters in force and the time zone, the kinds each a parameter of their own,
 * as the list sends them, and never a page size or a cursor, as the export follows every page itself. Same origin, so
 * the browser's own session goes with the download.
 */
export function auditExportHref(api: Api, query: AuditQuery): string {
  const params = new URLSearchParams()
  for (const kind of query.kind ?? []) params.append('kind', kind)
  const one = { userId: query.userId, matterId: query.matterId, docNumber: query.docNumber, from: query.from, to: query.to, zone: browserZone(), q: query.q }
  for (const [name, value] of Object.entries(one)) if (value !== undefined) params.append(name, String(value))
  return `${api.url('/v1/admin/audit/export')}?${params.toString()}`
}
