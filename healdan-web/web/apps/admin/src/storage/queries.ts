// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQuery, type QueryClient } from '@tanstack/react-query'
import { ApiError, apiPath, type adminComponents } from '@healdan/api'
import { ASK_ON_RETURN, useApi } from '@healdan/shell'
import { adminKey, adminKeys } from '../queries'

type A = adminComponents['schemas']

/**
 * The keys of what the Backends screens read, each under its route's own segments (adminKey), so `['admin', 'backends']`
 * covers the list, every backend's page and its key history, and `['admin', 'storage']` the settings and the stuck files
 * with no Store. A recovery key or a secret is never under any of them: only a dialog's own state holds one.
 */
export const storageKeys = {
  backends: adminKey('/v1/admin/backends'),
  backend: (id: number) => [...adminKey('/v1/admin/backends'), id] as const,
  keyEvents: (id: number) => [...adminKey('/v1/admin/backends'), id, 'key-events'] as const,
  storage: ['admin', 'storage'] as const,
  settings: adminKey('/v1/admin/storage/settings'),
}

/** The key a list of stuck files is kept under: its route's segments after /v1/admin, as adminKey makes them */
export function stuckKey(list: string): readonly unknown[] {
  return ['admin', ...list.split('/').filter(Boolean).slice(2)]
}

/**
 * `503 storage-unavailable`: the server has no storage module, so the Storage screens say they can't be shown there
 * rather than offer to try again
 */
export function isStorageUnavailable(error: unknown): boolean {
  return error instanceof ApiError && error.status === 503 && error.code === 'storage-unavailable'
}

/** A read of the Storage screens', asked again each time the window comes back, however fresh it is (spec 7.2) */
function useRead<T>(key: readonly unknown[], path: string, enabled = true) {
  const api = useApi()
  return useQuery({ queryKey: key, queryFn: ({ signal }) => api.get<T>(path, undefined, { signal }), enabled, meta: ASK_ON_RETURN })
}

/** Whether storage backends are on, every backend, and the files with no Store */
export function useBackends() {
  return useRead<A['BackendOverview']>(storageKeys.backends, '/v1/admin/backends')
}

/** A backend, for its page and its Edit window */
export function useBackend(id: number) {
  return useRead<A['Backend']>(storageKeys.backend(id), apiPath`/v1/admin/backends/${id}`)
}

/** Every showing and entering of a backend's recovery key, newest first: who and when, never the key */
export function useKeyEvents(id: number) {
  return useRead<A['KeyEvent'][]>(storageKeys.keyEvents(id), apiPath`/v1/admin/backends/${id}/key-events`)
}

/** The holding period, the read cache and parallel transfers, with each one's limits */
export function useStorageSettings(enabled = true) {
  return useRead<A['StorageSettings']>(storageKeys.settings, '/v1/admin/storage/settings', enabled)
}

/** The stuck files at a list's route: a Store's, or those with no Store, at most 1,000 */
export function useStuckFiles(list: string) {
  return useRead<A['StuckFile'][]>(stuckKey(list), list)
}

/**
 * After a backend changed (added, saved, tested, removed, its key shown, confirmed or entered): the list, every backend's
 * page and the key histories are asked again, with the Dashboard, whose Needs Attention and the left pane's count follow
 * a backend's status. A backend the server answered is drawn at once on its page.
 */
export function backendsChanged(queryClient: QueryClient, saved?: A['Backend']) {
  if (saved !== undefined) queryClient.setQueryData(storageKeys.backend(saved.id), saved)
  void queryClient.invalidateQueries({ queryKey: storageKeys.backends, predicate: ({ queryKey }) => saved === undefined || queryKey[2] !== saved.id || queryKey.length > 3 })
  void queryClient.invalidateQueries({ queryKey: adminKeys.dashboard })
}
