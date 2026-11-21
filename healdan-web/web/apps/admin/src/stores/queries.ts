// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQuery, type QueryClient } from '@tanstack/react-query'
import { apiPath, type adminComponents } from '@healdan/api'
import { ASK_ON_RETURN, useApi } from '@healdan/shell'
import { adminKey, adminKeys } from '../queries'
import { storageKeys, stuckKey } from '../storage/queries'

type A = adminComponents['schemas']

/**
 * The keys of what the Stores screens read, each under `['admin', 'stores']` (adminKeys.stores, the list itself and every
 * Store menu): the Stores in the Trash, a Store's page, who can see it, and its backend and files, with what a switch to
 * another backend would move, by the backend asked about.
 */
export const storeKeys = {
  list: adminKeys.stores,
  deleted: adminKey('/v1/admin/stores/deleted'),
  store: (id: number) => [...adminKeys.stores, id] as const,
  access: adminKeys.storeAccess,
  storage: (id: number) => [...adminKeys.stores, id, 'backend'] as const,
  switchPreview: (id: number, backendId: number) => [...adminKeys.stores, id, 'backend', { backendId }] as const,
}

/** A Store's stuck files' route, and its Retry's */
export function storeStuckRoutes(id: number) {
  const list = apiPath`/v1/admin/stores/${id}/stuck-files`
  return { list, retry: `${list}/retry` }
}

/** A read of the Stores screens', asked again each time the window comes back, however fresh it is (spec 7.2) */
function useRead<T>(key: readonly unknown[], path: string, query?: Record<string, number>, enabled = true) {
  const api = useApi()
  return useQuery({ queryKey: key, queryFn: ({ signal }) => api.get<T>(path, query, { signal }), enabled, meta: ASK_ON_RETURN })
}

/** The Stores in the Trash, deleted or removed from it, the most recently deleted first */
export function useDeletedStores() {
  return useRead<A['DeletedStore'][]>(storeKeys.deleted, '/v1/admin/stores/deleted')
}

/** A live Store, for its page: anything else is `404` */
export function useStore(id: number) {
  return useRead<A['StoreInfo']>(storeKeys.store(id), apiPath`/v1/admin/stores/${id}`)
}

/** Who can see a Store: each group and person whose row grants anything, as Can See or Custom */
export function useStoreAccess(id: number) {
  return useRead<A['StoreAccess']>(storeKeys.access(id), apiPath`/v1/admin/stores/${id}/access`)
}

/** A Store's backend and its files; nothing is asked while `enabled` is false */
export function useStoreStorage(id: number, enabled = true) {
  return useRead<A['StoreStorage']>(storeKeys.storage(id), apiPath`/v1/admin/stores/${id}/backend`, undefined, enabled)
}

/**
 * What would move were the Store given the backend (`switchPreview`), counted afresh each time it's asked, as files move
 * meanwhile, and forgotten once nothing shows it; nothing is asked without a backend
 */
export function useSwitchPreview(id: number, backendId: number | undefined) {
  const api = useApi()
  return useQuery({
    queryKey: storeKeys.switchPreview(id, backendId ?? -1),
    queryFn: ({ signal }) => api.get<A['StoreStorage']>(apiPath`/v1/admin/stores/${id}/backend`, { backendId: backendId ?? -1 }, { signal }),
    enabled: backendId !== undefined,
    staleTime: 0,
    gcTime: 0,
  })
}

/**
 * After a Store changed (made, renamed, its access or backend changed, deleted or restored): the list and every Store menu,
 * the Store's own page and the Stores in the Trash, and the Dashboard, whose Usage names the Stores and whose Needs
 * Attention counts their stuck files, are asked again. Who can see it, which a save puts in place itself, is left alone.
 * With `matters`, the lists of matters and the clients' pages too, which name a Store, or lose or regain its matters.
 */
export function storesChanged(queryClient: QueryClient, id?: number, { matters = false }: { matters?: boolean } = {}) {
  void queryClient.invalidateQueries({
    queryKey: adminKeys.stores,
    predicate: ({ queryKey }) => queryKey.length === 2 || queryKey[2] === 'deleted' || (id !== undefined && queryKey[2] === id && queryKey.length === 3),
  })
  void queryClient.invalidateQueries({ queryKey: adminKeys.dashboard })
  if (!matters) return
  void queryClient.invalidateQueries({ queryKey: adminKeys.matters })
  void queryClient.invalidateQueries({ queryKey: adminKeys.clients })
  void queryClient.invalidateQueries({ queryKey: adminKey('/v1/admin/find') })
}

/**
 * After a Store's files changed place (its backend changed, or its stuck files retried, which ask for their list again
 * themselves): its backend and files, its stuck files unless `stuck` is false, the backends' files, and the Stores
 */
export function storeFilesChanged(queryClient: QueryClient, id: number, { stuck = true }: { stuck?: boolean } = {}) {
  void queryClient.invalidateQueries({ queryKey: storeKeys.storage(id) })
  if (stuck) void queryClient.invalidateQueries({ queryKey: stuckKey(storeStuckRoutes(id).list) })
  void queryClient.invalidateQueries({ queryKey: storageKeys.backends })
  storesChanged(queryClient, id)
}
