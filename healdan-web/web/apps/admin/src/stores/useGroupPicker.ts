// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQueryClient } from '@tanstack/react-query'
import { useCallback } from 'react'
import type { adminComponents } from '@healdan/api'
import { useApi } from '@healdan/shell'
import { adminKey, useAdminQuery } from '../queries'

type A = adminComponents['schemas']

/** The groups offered while nothing is typed, the first by name: enough for most firms' every group */
const GROUPS_SHOWN = 50
/** The groups offered for what is typed */
const GROUPS_FOUND = 20

/**
 * What a list of who can see a Store adds from (spec 7.2: anything that can grow long is a search): the first groups by
 * name while nothing is typed, as SearchPicker's starting rows, and the groups found for what is, by `GET /v1/admin/groups`
 */
export function useGroupPicker() {
  const api = useApi()
  const queryClient = useQueryClient()
  const listed = useAdminQuery('/v1/admin/groups', { query: { limit: GROUPS_SHOWN } })
  const search = useCallback(
    async (q: string) => {
      const query = { q, limit: GROUPS_FOUND }
      const page = await queryClient.fetchQuery({
        queryKey: adminKey('/v1/admin/groups', query),
        queryFn: ({ signal }) => api.get<A['GroupList']>('/v1/admin/groups', query, { signal }),
        // A key back and forth needn't ask twice
        staleTime: 30_000,
      })
      return page.items ?? []
    },
    [api, queryClient],
  )
  return { initial: listed.data?.items ?? [], search }
}
