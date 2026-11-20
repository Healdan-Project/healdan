// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { keepPreviousData, useQuery, type UseQueryResult } from '@tanstack/react-query'
import type { adminComponents, adminPaths, Api } from '@healdan/api'
import { ASK_ON_RETURN, useApi } from '@healdan/shell'

type A = adminComponents['schemas']

/** How often the Dashboard, and whatever else polls, asks again while the page is visible (spec 7.2) */
export const POLL_MS = 30_000

/** The admin routes with a JSON answer to a GET */
export type AdminGetPath = {
  [P in keyof adminPaths]: adminPaths[P] extends { get: { responses: { 200: { content: { 'application/json': unknown } } } } } ? P : never
}[keyof adminPaths]

export type AttentionCode = A['AttentionCode']

/** What a GET of the route answers, as openapi-admin.yaml describes it */
export type AdminAnswer<P extends AdminGetPath> = adminPaths[P]['get']['responses'][200]['content']['application/json']

export type DashboardAnswer = AdminAnswer<'/v1/admin/dashboard'>
export type UsageAnswer = AdminAnswer<'/v1/admin/dashboard/usage'>

type QueryOf<P extends AdminGetPath> = adminPaths[P]['get']['parameters']['query']

export interface AdminQueryOptions<P extends AdminGetPath> {
  query?: QueryOf<P>
  /** False asks nothing, as for a find with nothing typed */
  enabled?: boolean
  /** Ask again every POLL_MS while the page is visible, never while it is hidden, and at each return to it */
  poll?: boolean
  /** Keep the last answer on screen while a new question is asked, so a list doesn't blink while typing */
  keepPrevious?: boolean
  staleTime?: number
}

/**
 * A route's key: `['admin', ...its segments after /v1/admin, its query]`, so `['admin', 'dashboard']` covers the
 * Dashboard and its usage alike, and nothing the shell or another app asks shares the key space.
 */
export function adminKey(path: AdminGetPath, query?: object): readonly unknown[] {
  const segments = path.split('/').filter(Boolean).slice(2)
  return query === undefined ? ['admin', ...segments] : ['admin', ...segments, query]
}

export const adminKeys = {
  dashboard: adminKey('/v1/admin/dashboard'),
}

/**
 * A GET of an admin route, typed from openapi-admin.yaml. Its refusals are the app's, not the screen's: a 401 or a
 * `403 admins-only` stops the app (access.tsx), whichever screen asked.
 */
export function useAdminQuery<P extends AdminGetPath>(path: P, options: AdminQueryOptions<P> = {}): UseQueryResult<AdminAnswer<P>> {
  const api = useApi()
  const query = options.query as Parameters<Api['get']>[1]
  return useQuery({
    queryKey: adminKey(path, query),
    queryFn: ({ signal }) => api.get<AdminAnswer<P>>(path, query, { signal }),
    enabled: options.enabled,
    staleTime: options.staleTime,
    placeholderData: options.keepPrevious ? keepPreviousData : undefined,
    // TanStack Query asks again on its interval only while its focusManager says the page is visible, which the
    // shell's query client tells it, and a return to the page asks however fresh the answer is (ASK_ON_RETURN)
    ...(options.poll ? { refetchInterval: POLL_MS, refetchIntervalInBackground: false, meta: ASK_ON_RETURN } : {}),
  })
}

/** The Dashboard's Needs Attention and Server Health, asked again every 30 seconds while the page is visible */
export function useDashboard(enabled = true) {
  return useAdminQuery('/v1/admin/dashboard', { poll: true, enabled })
}

/** The Dashboard's Usage, asked again as the rest of it is, its days cut in the browser's time zone, as the User app's are */
export function useUsage() {
  return useAdminQuery('/v1/admin/dashboard/usage', { poll: true, query: { zone: Intl.DateTimeFormat().resolvedOptions().timeZone } })
}
