// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQueryClient, type QueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import type { adminComponents, Api } from '@healdan/api'
import { useCursorList, type CursorListing, type CursorQuery } from '../lists/useCursorList'
import { adminKey } from '../queries'

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
export function useAuditList(query: AuditQuery): CursorListing<A['AuditRow'], A['AuditPage']> {
  return useCursorList('/v1/admin/audit', zoned(query), AUDIT_PAGE_SIZE)
}

/** The filters as every request of the list sends them, with the browser's time zone */
function zoned(query: AuditQuery): CursorQuery<'/v1/admin/audit'> {
  return { ...query, zone: browserZone() }
}

/**
 * After any change the app sent that succeeded: every list of the Audit Trail's, its screen's and the Activity tabs',
 * stale, as the change is recorded (P5-R34). A list on screen is asked again at once; one that isn't, when it's next shown.
 */
export function auditListsStale(queryClient: QueryClient): void {
  void queryClient.invalidateQueries({ queryKey: adminKey('/v1/admin/audit') })
}

/** Refresh: the list of these filters asked again, every page loaded, and whether that's on its way, which the button holds for */
export function useAuditRefresh(query: AuditQuery): { refresh: () => void; refreshing: boolean } {
  const queryClient = useQueryClient()
  const [refreshing, setRefreshing] = useState(false)
  return {
    refreshing,
    refresh: () => {
      if (refreshing) return
      setRefreshing(true)
      // useCursorList's key for the list
      void queryClient.refetchQueries({ queryKey: adminKey('/v1/admin/audit', zoned(query)), exact: true }).finally(() => setRefreshing(false))
    },
  }
}

/** Export CSV's check (P5-R36): the list with the same filters, one row of it, which the server answers as the export */
export function auditCheck(api: Api, query: AuditQuery): Promise<unknown> {
  return api.get('/v1/admin/audit', { ...zoned(query), limit: 1 } as Parameters<Api['get']>[1])
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
