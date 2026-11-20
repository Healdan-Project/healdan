// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { keepPreviousData, useQuery, type UseQueryResult } from '@tanstack/react-query'
import { apiPath, type adminComponents, type adminPaths, type Api } from '@healdan/api'
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
  /** Every list and search of clients, a client's page among them */
  clients: adminKey('/v1/admin/clients'),
  /** A client by its id, as its page and the Matters list's Client filter read it */
  client: (id: number) => [...adminKey('/v1/admin/clients'), id] as const,
  /** The number New Client suggests, under `clients` too */
  nextClientNumber: adminKey('/v1/admin/clients/next-number'),
  /** Every page of the Matters list, whatever its filters and order, and New Matter's number and checks */
  matters: adminKey('/v1/admin/matters'),
  /** The number New Matter suggests for a client, under `matters` too */
  nextMatterNumber: (clientId: number) => adminKey('/v1/admin/matters/next-number', { clientId }),
  /** New Matter's checks of its form, each by the form it checked, under `matters` too */
  matterCheck: [...adminKey('/v1/admin/matters'), 'check'] as const,
  /** Every Store menu, and who can see each Store */
  stores: adminKey('/v1/admin/stores'),
  storeAccess: (id: number) => [...adminKey('/v1/admin/stores'), id, 'access'] as const,
  templates: adminKey('/v1/admin/templates'),
  /** A workspace's tree, a template's or a matter's, by its top folder */
  workspaceTree: (folderId: number) => ['admin', 'workspaces', folderId, 'tree'] as const,
  groups: adminKey('/v1/admin/groups'),
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

/** Every Store, by name: a short list, for the Store menus (spec 7.2) */
export function useStores() {
  return useAdminQuery('/v1/admin/stores')
}

/** A client, for its page and to name the client a filter holds; nothing is asked without an id, or while `enabled` is false */
export function useClient(id: number | undefined, enabled = true) {
  const api = useApi()
  return useQuery({
    queryKey: adminKeys.client(id ?? -1),
    queryFn: ({ signal }) => api.get<A['Client']>(apiPath`/v1/admin/clients/${id ?? -1}`, undefined, { signal }),
    enabled: enabled && id !== undefined,
  })
}

/**
 * The number New Client suggests, the highest in use plus one: asked afresh each time the dialog opens, and forgotten
 * once it closes, as another administrator may have taken it meanwhile. Nothing is asked while `enabled` is false.
 */
export function useNextClientNumber(enabled = true) {
  const api = useApi()
  return useQuery({
    queryKey: adminKeys.nextClientNumber,
    queryFn: ({ signal }) => api.get<AdminAnswer<'/v1/admin/clients/next-number'>>('/v1/admin/clients/next-number', undefined, { signal }),
    enabled,
    staleTime: 0,
    gcTime: 0,
  })
}

/** Every workspace template, by name: a short list, for New Matter's Template menu (spec 7.2) */
export function useTemplates() {
  return useAdminQuery('/v1/admin/templates')
}

/** A workspace's folders, a template's or a matter's, by its top folder; nothing is asked without one */
export function useWorkspaceTree(folderId: number | undefined) {
  const api = useApi()
  return useQuery({
    queryKey: adminKeys.workspaceTree(folderId ?? -1),
    queryFn: ({ signal }) => api.get<A['WorkspaceNode']>(apiPath`/v1/admin/workspaces/${folderId ?? -1}/tree`, undefined, { signal }),
    enabled: folderId !== undefined,
  })
}

/**
 * The number New Matter suggests for the client's next matter: asked afresh for each client picked, and forgotten once
 * nothing shows it, as another administrator may take it meanwhile. Nothing is asked without a client.
 */
export function useNextMatterNumber(clientId: number | undefined) {
  const api = useApi()
  return useQuery({
    queryKey: adminKeys.nextMatterNumber(clientId ?? -1),
    queryFn: ({ signal }) => api.get<AdminAnswer<'/v1/admin/matters/next-number'>>('/v1/admin/matters/next-number', { clientId: clientId ?? -1 }, { signal }),
    enabled: clientId !== undefined,
    staleTime: 0,
    gcTime: 0,
  })
}
