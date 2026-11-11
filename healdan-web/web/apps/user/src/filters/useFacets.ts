// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { apiPath, type components } from '@healdan/api'
import { keys } from '../queries'
import { useApi } from '../services'
import { filterParams, type Filters } from './useFilters'

type S = components['schemas']

/** What a folder's panel offers: it has no client or matter to pick, as it's inside one. */
export const folderFacets = 'type,author,class'

interface Counted {
  documents: number
  facets?: S['Facets']
}

/**
 * How many documents each option would leave, asked apart from the list (which pages and sorts) and only while Filters
 * are open. The last answer stays on screen while the next is asked for, so the options don't blank at each tick.
 */
export function useFacets({ folderId, filters, enabled }: { folderId: number; filters: Filters; enabled: boolean }) {
  const api = useApi()
  const params = { ...filterParams(filters), facets: folderFacets, limit: 1 }
  return useQuery({
    queryKey: keys.facets(folderId, params),
    queryFn: () => api.get<Counted>(apiPath`/v1/folders/${folderId}/children`, params),
    select: (answer) => answer.facets,
    enabled,
    placeholderData: keepPreviousData,
  })
}

/** How many documents the folder holds with no filters, for "3 of 7": asked only while some filter is in use. */
export function useUnfilteredDocuments({ folderId, enabled }: { folderId: number; enabled: boolean }) {
  const api = useApi()
  return useQuery({
    queryKey: keys.unfiltered(folderId),
    queryFn: () => api.get<Counted>(apiPath`/v1/folders/${folderId}/children`, { limit: 1 }),
    select: (answer) => answer.documents,
    enabled,
  })
}
