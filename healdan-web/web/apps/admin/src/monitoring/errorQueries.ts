// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQuery, type QueryClient } from '@tanstack/react-query'
import { ApiError, apiPath, type Api, type adminComponents } from '@healdan/api'
import { ASK_ON_RETURN, useApi } from '@healdan/shell'
import { usePagedList, type PagedListing } from '../lists/usePagedList'
import { adminKey, adminKeys } from '../queries'

type A = adminComponents['schemas']

/** Faults come 100 at a time, as the end of the list comes into view, as the other lists' rows do */
export const ERRORS_PAGE = 100

/** The filters of `GET /v1/admin/errors`: how far back, the words, the area, and Unresolved */
export interface ErrorListQuery {
  window: '1h' | '24h' | '7d' | '30d'
  q?: string
  area?: A['ErrorArea']
  /** Only the faults nobody has marked resolved; left out for every fault */
  unresolved?: true
}

/**
 * The keys of what the Errors screen asks for, under `errors`: the list's pages by their filters, and each fault by its
 * id, so invalidating `errors` asks for both again
 */
export const errorKeys = {
  errors: adminKey('/v1/admin/errors'),
  error: (id: number) => [...adminKey('/v1/admin/errors'), id] as const,
}

/** The Errors list (spec 8.8), paged: `GET /v1/admin/errors`, 100 faults at a time, the one last seen first */
export function useErrorList(query: ErrorListQuery): PagedListing<A['ErrorRow']> {
  return usePagedList('/v1/admin/errors', query, ERRORS_PAGE)
}

/**
 * A fault, for its details below the list; nothing is asked without an id. Each return to the window asks again however
 * fresh it is, as the list does, since the fault may have been seen again meanwhile.
 */
export function useErrorDetail(id: number | undefined) {
  const api = useApi()
  return useQuery({
    queryKey: errorKeys.error(id ?? -1),
    queryFn: ({ signal }) => api.get<A['ErrorDetail']>(apiPath`/v1/admin/errors/${id ?? -1}`, undefined, { signal }),
    enabled: id !== undefined,
    meta: ASK_ON_RETURN,
  })
}

/**
 * A read of a fault that found it no more (`404`), as after the server deleted a resolved fault not seen for 90 days: the
 * details say it has gone, whatever was read of it before, and no re-read brings it back
 */
export function isGone(error: unknown): boolean {
  return error instanceof ApiError && error.status === 404
}

/** Mark Resolved, or Reopen for a resolved fault: the fault as the server then has it */
export function markError(api: Api, id: number, resolved: boolean): Promise<A['ErrorDetail']> {
  return resolved ? api.send<A['ErrorDetail']>('POST', apiPath`/v1/admin/errors/${id}/resolve`) : api.send<A['ErrorDetail']>('POST', apiPath`/v1/admin/errors/${id}/reopen`)
}

/**
 * After Mark Resolved or Reopen: the fault as the server answered, its list asked again, and the Dashboard's answer too,
 * whose Needs Attention and the left pane's Errors count both count the faults to resolve (P5-R20). The fault itself isn't
 * asked again, as the answer is it; a read of it on its way is cancelled first, so an older answer can't overwrite it.
 * The reads asked again aren't waited for: the change is done once the answer is in.
 */
export async function errorChanged(queryClient: QueryClient, detail: A['ErrorDetail']): Promise<void> {
  const key = errorKeys.error(detail.id)
  await queryClient.cancelQueries({ queryKey: key, exact: true })
  queryClient.setQueryData(key, detail)
  // The list's pages are keyed by their filters, an object, where a fault's own key has its id
  void queryClient.invalidateQueries({ queryKey: errorKeys.errors, predicate: (query) => typeof query.queryKey[2] === 'object' })
  void queryClient.invalidateQueries({ queryKey: adminKeys.dashboard })
}
