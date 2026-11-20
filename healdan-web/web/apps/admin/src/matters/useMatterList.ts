// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { keepPreviousData, useInfiniteQuery } from '@tanstack/react-query'
import { useMemo } from 'react'
import type { adminComponents } from '@healdan/api'
import { ASK_ON_RETURN, useApi } from '@healdan/shell'
import { adminKey, type AdminAnswer } from '../queries'

type A = adminComponents['schemas']

/** Rows come 100 at a time, as the end of the list comes into view, as the User app's lists do */
export const MATTERS_PAGE = 100

/** What the server sorts the Matters list by, which are also its columns' ids */
export const matterSorts = ['number', 'name', 'client', 'store', 'owner', 'template', 'documents', 'bytes', 'lastActivity'] as const
export type MatterSort = (typeof matterSorts)[number]
export type SortOrder = 'asc' | 'desc'

export function isMatterSort(value: unknown): value is MatterSort {
  return matterSorts.some((sort) => sort === value)
}

/** The filters and order of `GET /v1/admin/matters`: any left out is no filter, and the server's own order, by number */
export interface MatterListQuery {
  q?: string
  clientId?: number
  storeId?: number
  ownerId?: number
  sort?: MatterSort
  order?: SortOrder
}

export interface MatterListing {
  /** The matters, as far as they are loaded, each once */
  matters: A['MatterRow'][]
  /** How many match, loaded or not */
  total?: number
  /** When the summary that gives Documents, Size and Last Activity was worked out; null until it has been */
  figuresAt?: string | null
  /** Loads the next page; undefined once every row is loaded, and while the rows are an older question's */
  loadMore?: () => void
  isLoadingMore: boolean
  /** The rows are the last question's, kept while a new order or filter loads, so the list doesn't blank */
  isStale: boolean
  isPending: boolean
  isError: boolean
  error: unknown
  refetch: () => void
}

type Page = AdminAnswer<'/v1/admin/matters'>

/**
 * The Matters list, paged (spec 8.3): TanStack Query's infinite query over `GET /v1/admin/matters`, 100 at a time, the
 * last question's rows kept while a new one loads, and asked again on each return to the window, as the User app's
 * lists are (spec 7.2).
 */
export function useMatterList(query: MatterListQuery): MatterListing {
  const api = useApi()
  const asked = useInfiniteQuery({
    queryKey: adminKey('/v1/admin/matters', query),
    queryFn: ({ pageParam, signal }) => api.get<Page>('/v1/admin/matters', { ...query, offset: pageParam, limit: MATTERS_PAGE }, { signal }),
    initialPageParam: 0,
    getNextPageParam: (last) => {
      const next = last.offset + (last.items?.length ?? 0)
      // An empty page ends it too, should matters have gone since the count
      return (last.items?.length ?? 0) > 0 && next < (last.total ?? 0) ? next : undefined
    },
    placeholderData: keepPreviousData,
    meta: ASK_ON_RETURN,
  })
  const pages = asked.data?.pages
  const matters = useMemo(() => distinct(pages?.flatMap((page) => page.items ?? []) ?? []), [pages])
  const last = pages?.at(-1)
  return {
    matters,
    total: last?.total ?? undefined,
    figuresAt: last?.figuresAt,
    // Asked again while a page is coming, it waits for that one rather than asking twice
    loadMore: asked.hasNextPage && !asked.isPlaceholderData ? () => void asked.fetchNextPage({ cancelRefetch: false }) : undefined,
    isLoadingMore: asked.isFetchingNextPage,
    isStale: asked.isPlaceholderData,
    isPending: asked.isPending,
    isError: asked.isError,
    error: asked.error,
    refetch: () => void asked.refetch(),
  }
}

/** The matters, each once: offset pages shift when matters come or go between them, so a page can repeat the last one's end */
function distinct(matters: A['MatterRow'][]): A['MatterRow'][] {
  const seen = new Set<number>()
  return matters.filter((matter) => {
    if (seen.has(matter.id)) return false
    seen.add(matter.id)
    return true
  })
}
