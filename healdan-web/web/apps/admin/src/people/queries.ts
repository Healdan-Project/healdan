// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQuery, type QueryClient } from '@tanstack/react-query'
import { apiPath, type adminComponents } from '@healdan/api'
import { ASK_ON_RETURN, useApi } from '@healdan/shell'
import { usePagedList, type PagedListing, type SortOrder } from '../lists/usePagedList'
import { adminKey, adminKeys, type AdminAnswer } from '../queries'

type A = adminComponents['schemas']

/** Rows come 100 at a time, as the end of the list comes into view, as the other lists' do */
export const USERS_PAGE = 100

/** What the server sorts the Users list by, which are also its columns' ids */
export const userSorts = ['name', 'username', 'lastSignIn'] as const
export type UserSort = (typeof userSorts)[number]

export function isUserSort(value: unknown): value is UserSort {
  return userSorts.some((sort) => sort === value)
}

export const userStatuses = ['active', 'disabled', 'blocked'] as const satisfies readonly A['UserStatus'][]

export function isUserStatus(value: unknown): value is A['UserStatus'] {
  return userStatuses.some((status) => status === value)
}

/** The filters and order of `GET /v1/admin/users`: none is everyone, by name */
export interface UserListQuery {
  q?: string
  groupId?: number
  status?: A['UserStatus']
  sort?: UserSort
  order?: SortOrder
}

/**
 * The keys of what the Users screens ask for, all under `users`: the lists by their filters, and a person by their id,
 * with their devices and check-outs under them; the options under `users` too.
 */
export const peopleKeys = {
  users: adminKey('/v1/admin/users'),
  user: (id: number) => [...adminKey('/v1/admin/users'), id] as const,
  devices: (id: number) => [...adminKey('/v1/admin/users'), id, 'devices'] as const,
  checkouts: (id: number) => [...adminKey('/v1/admin/users'), id, 'checkouts'] as const,
  options: adminKey('/v1/admin/users/options'),
}

/** The Users list, paged (spec 8.7): `GET /v1/admin/users`, 100 at a time, as usePagedList asks for every list */
export function useUserList(query: UserListQuery): PagedListing<A['UserRow']> {
  return usePagedList('/v1/admin/users', query, USERS_PAGE)
}

/**
 * A person, for their page, and to name the person a filter holds (the Audit Trail's); nothing is asked while `enabled` is
 * false. Each return to the window asks again however fresh it is (spec 7.2).
 */
export function useUser(id: number, enabled = true) {
  const api = useApi()
  return useQuery({
    queryKey: peopleKeys.user(id),
    queryFn: ({ signal }) => api.get<A['User']>(apiPath`/v1/admin/users/${id}`, undefined, { signal }),
    enabled,
    meta: ASK_ON_RETURN,
  })
}

/** What New User and Edit offer: the languages, the time zones, the providers that are on and whether email can go */
export function useUserOptions(enabled = true) {
  const api = useApi()
  return useQuery({
    queryKey: peopleKeys.options,
    queryFn: ({ signal }) => api.get<AdminAnswer<'/v1/admin/users/options'>>('/v1/admin/users/options', undefined, { signal }),
    enabled,
    // A form opening again needn't ask; a return to the window does
    staleTime: 60_000,
    meta: ASK_ON_RETURN,
  })
}

/** A person's devices, newest first, the whole list */
export function useUserDevices(id: number) {
  const api = useApi()
  return useQuery({
    queryKey: peopleKeys.devices(id),
    queryFn: ({ signal }) => api.get<A['UserDevice'][]>(apiPath`/v1/admin/users/${id}/devices`, undefined, { signal }),
    meta: ASK_ON_RETURN,
  })
}

/** What a person has checked out, the latest first, the whole list */
export function useUserCheckouts(id: number) {
  const api = useApi()
  return useQuery({
    queryKey: peopleKeys.checkouts(id),
    queryFn: ({ signal }) => api.get<A['UserCheckout'][]>(apiPath`/v1/admin/users/${id}/checkouts`, undefined, { signal }),
    meta: ASK_ON_RETURN,
  })
}

/**
 * After a change to people: the person's page has the server's answer at once, when there is one; every list of people,
 * the find and the groups (whose member counts may have changed) are asked again. The options aren't, as no change to a
 * person changes them, and neither is the person's own page, which has its answer.
 */
export function peopleChanged(queryClient: QueryClient, user?: A['User'], also: { devices?: boolean; checkouts?: boolean; matters?: boolean } = {}) {
  if (user !== undefined) queryClient.setQueryData(peopleKeys.user(user.id), user)
  void queryClient.invalidateQueries({ queryKey: peopleKeys.users, predicate: ({ queryKey }) => typeof queryKey[2] === 'object' && queryKey[2] !== null })
  void queryClient.invalidateQueries({ queryKey: adminKey('/v1/admin/find') })
  void queryClient.invalidateQueries({ queryKey: adminKeys.groups })
  if (user !== undefined && also.devices) void queryClient.invalidateQueries({ queryKey: peopleKeys.devices(user.id) })
  if (user !== undefined && also.checkouts) void queryClient.invalidateQueries({ queryKey: peopleKeys.checkouts(user.id) })
  // The matters' rows name their owners
  if (also.matters) void queryClient.invalidateQueries({ queryKey: adminKeys.matters })
}

/** After a change whose answer isn't the person: their page is asked again, with the lists, and what else the change touched */
export function personChanged(queryClient: QueryClient, id: number, also: { devices?: boolean; checkouts?: boolean } = {}) {
  void queryClient.invalidateQueries({ queryKey: peopleKeys.user(id), exact: true })
  peopleChanged(queryClient)
  if (also.devices) void queryClient.invalidateQueries({ queryKey: peopleKeys.devices(id) })
  if (also.checkouts) void queryClient.invalidateQueries({ queryKey: peopleKeys.checkouts(id) })
}
