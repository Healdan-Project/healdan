// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { keepPreviousData, useInfiniteQuery } from '@tanstack/react-query'
import { useMemo } from 'react'
import { apiPath, type components } from '@healdan/api'
import type { TableSort } from '@healdan/ui'
import { filterParams, noFilters, type Filters } from '../filters/useFilters'
import { keys } from '../queries'
import { useApi } from '../services'

type S = components['schemas']

export type Child = S['FolderView'] | S['DocumentView']
/** What a list can hold: a folder's children, and a search's hits, which can be matters too */
export type Hit = Child | S['MatterView']
type ChildPage = Omit<S['FolderChildPage'], 'items'> & { items: Child[] }

/** What the API sorts a folder's documents by (ruling R4) */
export const sortColumns = ['title', 'docNumber', 'version', 'author', 'edited'] as const
export type SortColumn = (typeof sortColumns)[number]
export type ListingOrder = 'asc' | 'desc'

export function isSortColumn(value: unknown): value is SortColumn {
  return sortColumns.some((column) => column === value)
}

/** Rows come 100 at a time, as the end of the list comes into view (spec section 10.2). */
export const PAGE_SIZE = 100

export interface ListingOptions {
  folderId: number
  /** None keeps the server's own order: by title, or a search folder's newest change first */
  sort?: { column: SortColumn; direction: TableSort['direction'] }
  /** Only documents that pass them are listed, and counted */
  filters?: Filters
  /** False asks nothing, as for a Store, which has no list */
  enabled?: boolean
}

export interface Listing {
  /** The subfolders, then the documents, as far as they are loaded */
  rows: Child[]
  /** How many rows there are in all */
  total?: number
  /** How many of them are documents */
  documents?: number
  /** Loads the next page; undefined once every row is loaded, and while the rows are stale */
  loadMore?: () => void
  isLoadingMore: boolean
  /** The rows are the last order's, kept while a new one loads, so the list doesn't blank */
  isStale: boolean
  isPending: boolean
  isError: boolean
  error: unknown
  refetch: () => void
}

/** A folder's children, paged: TanStack Query's infinite query over GET /folders/{id}/children. */
export function useListing({ folderId, sort, filters = noFilters, enabled = true }: ListingOptions): Listing {
  const api = useApi()
  const params = {
    ...(sort ? { sort: sort.column, order: sort.direction === 'descending' ? 'desc' : 'asc' } : {}),
    ...filterParams(filters),
  }
  const query = useInfiniteQuery({
    queryKey: keys.children(folderId, params),
    queryFn: ({ pageParam }) =>
      api.get<ChildPage>(apiPath`/v1/folders/${folderId}/children`, { ...params, offset: pageParam, limit: PAGE_SIZE }),
    initialPageParam: 0,
    getNextPageParam: (last) => {
      const next = last.offset + last.items.length
      // An empty page ends it too, should documents have gone since the count
      return last.items.length > 0 && next < (last.total ?? 0) ? next : undefined
    },
    enabled,
    // A new order keeps the old rows on screen until its first page comes
    placeholderData: keepPreviousData,
  })

  const pages = query.data?.pages
  const rows = useMemo(() => distinct(pages?.flatMap((page) => page.items) ?? []), [pages])
  const last = pages?.at(-1)
  return {
    rows,
    total: last?.total ?? undefined,
    documents: last?.documents,
    ...pagedState(query, enabled),
  }
}

/** What a paged list's query says about itself, which a folder's listing and a search's results both show the same way. */
export function pagedState(
  query: {
    hasNextPage: boolean
    isPlaceholderData: boolean
    isFetchingNextPage: boolean
    isPending: boolean
    isError: boolean
    error: unknown
    fetchNextPage: (options?: { cancelRefetch?: boolean }) => unknown
    refetch: () => unknown
  },
  enabled: boolean,
) {
  return {
    // Asked again while a page is coming, it waits for that one rather than asking twice
    loadMore: query.hasNextPage && !query.isPlaceholderData ? () => void query.fetchNextPage({ cancelRefetch: false }) : undefined,
    isLoadingMore: query.isFetchingNextPage,
    isStale: query.isPlaceholderData,
    isPending: enabled && query.isPending,
    isError: query.isError,
    error: query.error,
    refetch: () => void query.refetch(),
  }
}

/** A row's key: a folder's, a matter's and a document's numbers can be the same, so each says which it is. */
export function childKey(item: Hit): string {
  if (item.type === 'matter') return `m${item.id}`
  return item.type === 'folder' ? `f${item.id}` : `d${item.docNumber}`
}

/**
 * The rows, each once. Offset pages shift when the folder changes between them (a document filed meanwhile that sorts
 * before the loaded rows), so a page can repeat the last one's end: the first copy stays. One that a deletion skips
 * comes back when the list is next asked for, as when the window regains focus.
 */
export function distinct<T extends Hit>(items: T[]): T[] {
  const seen = new Set<string>()
  return items.filter((item) => {
    const key = childKey(item)
    if (seen.has(key)) return false
    seen.add(key)
    return true
  })
}
