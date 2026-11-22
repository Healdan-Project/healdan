// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQuery, type QueryClient } from '@tanstack/react-query'
import { apiPath, type adminComponents } from '@healdan/api'
import { ASK_ON_RETURN, useApi } from '@healdan/shell'
import { usePagedList, type PagedListing } from '../lists/usePagedList'
import { adminKeys } from '../queries'
import { peopleKeys } from './queries'

type A = adminComponents['schemas']

/** Groups and members come 100 at a time, as the end of the list comes into view or Show More is pressed */
export const GROUPS_PAGE = 100

/**
 * The keys of what the Groups screens ask for, all under `groups`, where every group menu and search is too: a group by its
 * id (the key the Users list's Group filter names one by), and its members, by their words, under it.
 */
export const groupKeys = {
  groups: adminKeys.groups,
  group: (id: number) => [...adminKeys.groups, id] as const,
  members: (id: number) => [...adminKeys.groups, id, 'members'] as const,
}

/** The Groups list, paged (spec 8.7): `GET /v1/admin/groups`, by name, those whose name holds the words */
export function useGroupList(q: string | undefined): PagedListing<A['Group']> {
  return usePagedList('/v1/admin/groups', { q }, GROUPS_PAGE)
}

/** A group's members, paged, by name: `GET /v1/admin/groups/{id}/members`, those whose name, username or email holds the words */
export function useGroupMembers(id: number, q: string | undefined): PagedListing<A['GroupMember']> {
  return usePagedList('/v1/admin/groups/{id}/members', { q }, GROUPS_PAGE, { id })
}

/** A group, for its page. Each return to the window asks again however fresh it is (spec 7.2). */
export function useGroup(id: number) {
  const api = useApi()
  return useQuery({
    queryKey: groupKeys.group(id),
    queryFn: ({ signal }) => api.get<A['GroupDetail']>(apiPath`/v1/admin/groups/${id}`, undefined, { signal }),
    meta: ASK_ON_RETURN,
  })
}

/**
 * After a change to a group's name, description or existence: its page has the server's answer at once, when there is one,
 * and everything else the app holds is asked again when next shown, as a group's name is in many answers (the people's
 * groups, a Store's and a folder's access, the group menus). Only what's on screen is asked at once.
 */
export function groupChanged(queryClient: QueryClient, group?: A['GroupDetail']) {
  if (group !== undefined) queryClient.setQueryData(groupKeys.group(group.id), group)
  void queryClient.invalidateQueries({
    queryKey: ['admin'],
    predicate: ({ queryKey }) => group === undefined || queryKey.length !== 3 || queryKey[1] !== 'groups' || queryKey[2] !== group.id,
  })
}

/**
 * After a member is added or taken out: every group and list of groups (the group's count, its members, the others'
 * counts) is asked again, and so is every person's page and list of people, which name their groups.
 */
export function membersChanged(queryClient: QueryClient) {
  void queryClient.invalidateQueries({ queryKey: groupKeys.groups })
  void queryClient.invalidateQueries({ queryKey: peopleKeys.users })
}
