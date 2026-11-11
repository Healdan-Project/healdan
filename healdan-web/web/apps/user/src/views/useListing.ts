// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useInfiniteQuery } from '@tanstack/react-query'
import { useMemo } from 'react'
import { apiPath, type components } from '@healdan/api'
import type { TableSort } from '@healdan/ui'
import { keys } from '../queries'
import { useApi } from '../services'

type S = components['schemas']

export type Child = S['FolderView'] | S['DocumentView']
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
  /** Loads the next page; undefined once every row is loaded */
  loadMore?: () => void
  isLoadingMore: boolean
  isPending: boolean
  isError: boolean
  error: unknown
  refetch: () => void
}

/** A folder's children, paged: TanStack Query's infinite query over GET /folders/{id}/children. */
export function useListing({ folderId, sort, enabled = true }: ListingOptions): Listing {
  const api = useApi()
  const order = sort ? { sort: sort.column, order: sort.direction === 'descending' ? 'desc' : 'asc' } : {}
  const query = useInfiniteQuery({
    queryKey: keys.children(folderId, order),
    queryFn: ({ pageParam }) =>
      api.get<ChildPage>(apiPath`/v1/folders/${folderId}/children`, { ...order, offset: pageParam, limit: PAGE_SIZE }),
    initialPageParam: 0,
    getNextPageParam: (last) => {
      const next = last.offset + last.items.length
      // An empty page ends it too, should documents have gone since the count
      return last.items.length > 0 && next < (last.total ?? 0) ? next : undefined
    },
    enabled,
  })

  const pages = query.data?.pages
  const rows = useMemo(() => pages?.flatMap((page) => page.items) ?? [], [pages])
  const last = pages?.at(-1)
  const { fetchNextPage, refetch } = query
  return {
    rows,
    total: last?.total ?? undefined,
    documents: last?.documents,
    // Asked again while a page is coming, it waits for that one rather than asking twice
    loadMore: query.hasNextPage ? () => void fetchNextPage({ cancelRefetch: false }) : undefined,
    isLoadingMore: query.isFetchingNextPage,
    isPending: enabled && query.isPending,
    isError: query.isError,
    error: query.error,
    refetch: () => void refetch(),
  }
}
