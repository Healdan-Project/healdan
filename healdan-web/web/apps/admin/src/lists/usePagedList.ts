// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { keepPreviousData, useInfiniteQuery } from '@tanstack/react-query'
import { useMemo } from 'react'
import { ASK_ON_RETURN, useApi } from '@healdan/shell'
import type { AdminAnswer } from '../queries'

/**
 * The lists that come a page at a time: clients and matters, each with when their figures were worked out, people, groups,
 * a group's members, whose route names the group by its `{id}`, and the faults the server logged
 */
export type PagedPath = '/v1/admin/clients' | '/v1/admin/matters' | '/v1/admin/users' | '/v1/admin/groups' | '/v1/admin/groups/{id}/members' | '/v1/admin/errors'

/** Which way a list's server sorts it */
export type SortOrder = 'asc' | 'desc'

/** One row of such a list */
export type PagedItem<P extends PagedPath> = NonNullable<AdminAnswer<P>['items']>[number]

export interface PagedListing<T> {
  /** The rows, as far as they are loaded, each once */
  items: T[]
  /** How many match, loaded or not */
  total?: number
  /** When the summary that gives the list's figures was worked out; null until it has been; undefined for a list with none */
  figuresAt?: string | null
  /** How many times the faults that match were seen, every page's together (the Errors list's); undefined for another list */
  errors?: number
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

/**
 * The route with each `{name}` of its path given by `ids`, as one segment, and its key, as adminKey makes one, but with
 * each id as the number it is: a group's members are under `['admin', 'groups', 7]`, as the group itself is.
 */
function located(path: PagedPath, ids: Readonly<Record<string, number>>): { url: string; key: readonly unknown[] } {
  const segments = path.split('/').filter(Boolean)
  const id = (segment: string) => {
    const name = /^\{(.+)\}$/.exec(segment)?.[1]
    return name === undefined ? undefined : ids[name]
  }
  if (segments.some((segment) => segment.startsWith('{') && id(segment) === undefined)) throw new Error(`No id for ${path}`)
  return {
    url: `/${segments.map((segment) => (id(segment) === undefined ? segment : encodeURIComponent(String(id(segment))))).join('/')}`,
    // The segments after /v1/admin, as adminKey's
    key: ['admin', ...segments.slice(2).map((segment) => id(segment) ?? segment)],
  }
}

/**
 * A list of the Admin app's, paged (spec 7.2, 8.2, 8.3, 8.7): TanStack Query's infinite query over the route, `pageSize` rows
 * at a time from an offset, the last question's rows kept while a new one loads, and asked again on each return to the
 * window, as the User app's lists are. `query` is the list's filters and order; it never holds an offset or a limit. A
 * route whose path names something by its id, as a group's members do, has it in `ids`.
 */
export function usePagedList<P extends PagedPath>(path: P, query: object, pageSize: number, ids: Readonly<Record<string, number>> = {}): PagedListing<PagedItem<P>> {
  const api = useApi()
  const { url, key } = located(path, ids)
  const asked = useInfiniteQuery({
    queryKey: [...key, query],
    queryFn: ({ pageParam, signal }) => api.get<AdminAnswer<P>>(url, { ...query, offset: pageParam, limit: pageSize }, { signal }),
    initialPageParam: 0,
    getNextPageParam: (last) => {
      const next = last.offset + (last.items?.length ?? 0)
      // An empty page ends it too, should rows have gone since the count
      return (last.items?.length ?? 0) > 0 && next < (last.total ?? 0) ? next : undefined
    },
    placeholderData: keepPreviousData,
    meta: ASK_ON_RETURN,
  })
  const pages = asked.data?.pages
  const items = useMemo(() => distinct(pages?.flatMap((page): PagedItem<P>[] => page.items ?? []) ?? []), [pages])
  const last = pages?.at(-1)
  return {
    items,
    total: last?.total ?? undefined,
    figuresAt: last !== undefined && 'figuresAt' in last ? last.figuresAt : undefined,
    errors: last !== undefined && 'errors' in last ? last.errors : undefined,
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

/** The rows, each once: offset pages shift when rows come or go between them, so a page can repeat the last one's end */
function distinct<T extends { id: number }>(rows: T[]): T[] {
  const seen = new Set<number>()
  return rows.filter((row) => {
    if (seen.has(row.id)) return false
    seen.add(row.id)
    return true
  })
}
