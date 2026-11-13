// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { keepPreviousData, useInfiniteQuery, useQuery } from '@tanstack/react-query'
import { useMemo } from 'react'
import type { components } from '@healdan/api'
import type { TableSort } from '@healdan/ui'
import { filterParams, noFilters, type Filters } from '../filters/useFilters'
import { keys } from '../queries'
import { useApi } from '../services'
import { PAGE_SIZE, distinct, pagedState, type Hit, type SortColumn } from '../views/useListing'
import { searchesDocuments, type SearchScope } from './scopes'

type S = components['schemas']

/** What a search page is, with the hits typed by what they are */
type SearchPage = Omit<S['SearchPage'], 'items'> & { items: Hit[] }

/** Every count a search's Filters can show (the folder's panel has no Client or Matter) */
export const searchFacets = 'type,author,class,client,matter'

export interface SearchOptions {
  scope: SearchScope
  /** The words, trimmed; nothing is asked while they're empty */
  q: string
  sort?: { column: SortColumn; direction: TableSort['direction'] }
  filters?: Filters
}

// Only the document scopes take filters and sorting: the API refuses them with 400 for the others, so an address that
// holds some never sends them.
function paramsOf({ scope, q, sort, filters = noFilters }: SearchOptions): Record<string, string | string[]> {
  const params: Record<string, string | string[]> = { scope, q }
  if (!searchesDocuments(scope)) return params
  if (sort) Object.assign(params, { sort: sort.column, order: sort.direction === 'descending' ? 'desc' : 'asc' })
  return { ...params, ...filterParams(filters) }
}

/**
 * Where the next page starts, or undefined at the end. A counted search ends at its `total`; one the server stopped
 * counting (`total` null) ends at the first page shorter than the 100 asked, as the server fills pages from the hits
 * that follow the ones skipped. An empty page ends either.
 */
export function nextOffset(last: { items: unknown[]; total?: number | null }, loaded: number): number | undefined {
  if (last.items.length === 0) return undefined
  if (last.total !== null && last.total !== undefined) return loaded < last.total ? loaded : undefined
  return last.items.length >= PAGE_SIZE ? loaded : undefined
}

export interface SearchResults {
  rows: Hit[]
  /** How many hits there are, when the server counted them all */
  total?: number
  /** How many it found before it stopped counting, when there are more */
  totalAtLeast?: number
  loadMore?: () => void
  isLoadingMore: boolean
  /** The rows are the last search's, kept while a new order or filter loads */
  isStale: boolean
  isPending: boolean
  isError: boolean
  error: unknown
  refetch: () => void
}

/** A search's hits, 100 at a time: TanStack Query's infinite query over GET /search. */
export function useSearchResults(options: SearchOptions): SearchResults {
  const api = useApi()
  const params = paramsOf(options)
  const enabled = options.q !== ''
  const query = useInfiniteQuery({
    queryKey: keys.search(params),
    queryFn: ({ pageParam }) => api.get<SearchPage>('/v1/search', { ...params, offset: pageParam, limit: PAGE_SIZE }),
    initialPageParam: 0,
    getNextPageParam: (last, all) => nextOffset(last, all.reduce((n, page) => n + page.items.length, 0)),
    enabled,
    placeholderData: keepPreviousData,
    // Coming back to the window would ask every loaded page again, one after another, and each page runs the whole
    // search on the server: a search is asked again when it's run again, not on focus
    refetchOnWindowFocus: false,
  })
  const pages = query.data?.pages
  const rows = useMemo(() => distinct(pages?.flatMap((page) => page.items) ?? []), [pages])
  const last = pages?.at(-1)
  return { rows, total: last?.total ?? undefined, totalAtLeast: last?.totalAtLeast, ...pagedState(query, enabled) }
}

/**
 * The options' counts for a search's Filters, asked apart from the list and only while the panel shows them. The last
 * answer stays on screen while the next is asked for. `partial` says the counts cover only the most relevant matches.
 */
export function useSearchFacets({ scope, q, filters, enabled }: { scope: SearchScope; q: string; filters: Filters; enabled: boolean }) {
  const api = useApi()
  const params = { ...paramsOf({ scope, q, filters }), facets: searchFacets, limit: 1 }
  return useQuery({
    queryKey: keys.searchFacets(params),
    queryFn: () => api.get<SearchPage>('/v1/search', params),
    select: (answer) => answer.facets,
    enabled: enabled && q !== '' && searchesDocuments(scope),
    placeholderData: keepPreviousData,
    // As for the results beside them: each count runs the whole search on the server, so a return to the window asks nothing
    refetchOnWindowFocus: false,
  })
}
