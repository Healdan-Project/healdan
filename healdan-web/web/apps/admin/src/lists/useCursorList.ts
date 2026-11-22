// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { hashKey, keepPreviousData, useInfiniteQuery } from '@tanstack/react-query'
import { useEffect, useMemo, useState } from 'react'
import type { adminPaths, Api } from '@healdan/api'
import { ASK_ON_RETURN, useApi } from '@healdan/shell'
import { adminKey, type AdminAnswer, type AdminGetPath } from '../queries'

/** A page of a list that comes by cursor: its rows, and where the next page starts, null at the end */
interface CursorPage<T> {
  items: T[]
  nextCursor: string | null
}

/** The lists that come a page at a time by cursor, each answer's `nextCursor` sent back as `cursor`: the Audit Trail */
export type CursorPath = {
  [P in AdminGetPath]: AdminAnswer<P> extends CursorPage<unknown> ? P : never
}[AdminGetPath]

/** One row of such a list */
export type CursorItem<P extends CursorPath> = AdminAnswer<P> extends CursorPage<infer T> ? T : never

/** Such a list's filters, as its route takes them: never the cursor or the limit, which the list sends itself */
export type CursorQuery<P extends CursorPath> = Omit<NonNullable<adminPaths[P]['get']['parameters']['query']>, 'cursor' | 'limit'>

/**
 * How many pages in a row the list asks for by itself after a page that came back short with a cursor (P5-R22), before
 * it stops and offers more: the server reads only so far into each source for a page, so a narrow filter's pages can be
 * short, or empty, well before the end.
 */
export const FOLLOW_SHORT_PAGES = 3

export interface CursorListing<T> {
  /** The rows, as far as they are loaded, in the server's order */
  items: T[]
  /**
   * More can be asked for, with showMore: the last page came with a cursor, and the list isn't asking for the next one
   * itself. False at the end, while the next page is on its way, and while the rows are an older question's.
   */
  hasMore: boolean
  /** Asks for the next page, then goes on by itself over short ones again; nothing while hasMore is false */
  showMore: () => void
  /** A page after the first is on its way, asked for or followed */
  isLoadingMore: boolean
  /** Every row is loaded: the last page came with no cursor */
  atEnd: boolean
  /** An answer is shown, this question's or, while it loads, the last one's: a failed read then keeps it (StaleNote) */
  isLoaded: boolean
  /** The rows are the last question's, kept while a new filter loads, so the list doesn't blank */
  isStale: boolean
  isPending: boolean
  isError: boolean
  error: unknown
  /** Asks again for every page loaded, as Try Again does */
  refetch: () => void
}

/**
 * A list of the Admin app's that comes by cursor (P5-R22): TanStack Query's infinite query over the route, `pageSize`
 * rows at a time, each page after the first asked for with the cursor the last one gave. The last question's rows stay
 * while a new one loads, and the list is asked again on each return to the window, as usePagedList's lists are. A page
 * that comes back short with a cursor is followed by the next, asked for by the list itself, FOLLOW_SHORT_PAGES in a row
 * at most; then it stops, and `hasMore` offers the rest to Show More, which starts such a run again. A read that fails
 * keeps the rows shown and says so (`isError` with `isLoaded`). `query` is the list's filters; changing it starts afresh.
 */
export function useCursorList<P extends CursorPath>(path: P, query: CursorQuery<P>, pageSize: number): CursorListing<CursorItem<P>> {
  const api = useApi()
  const queryKey = adminKey(path, query)
  const asked = useInfiniteQuery({
    queryKey,
    queryFn: ({ pageParam, signal }) =>
      api.get<CursorPage<CursorItem<P>>>(path, { ...query, limit: pageSize, ...(pageParam === undefined ? {} : { cursor: pageParam }) } as Parameters<Api['get']>[1], { signal }),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    placeholderData: keepPreviousData,
    meta: ASK_ON_RETURN,
  })

  // Where the run of pages that Show More (or the first page) started is, for these filters: the pages after it were
  // followed by the list itself. Other filters start at their first page.
  const question = hashKey(queryKey)
  const [run, setRun] = useState({ question, start: 0 })
  const start = run.question === question ? run.start : 0

  const pages = asked.data?.pages
  const last = pages?.at(-1)
  const own = !asked.isPlaceholderData
  const more = own && last !== undefined && last.nextCursor !== null
  const followed = (pages?.length ?? 0) - 1 - start
  const follow = more && !asked.isFetching && !asked.isError && last.items.length < pageSize && followed < FOLLOW_SHORT_PAGES
  const { fetchNextPage } = asked
  useEffect(() => {
    // Never in place of a read under way, which it waits for
    if (follow) void fetchNextPage({ cancelRefetch: false })
  }, [follow, last, fetchNextPage])

  const hasMore = more && !follow && !asked.isFetchingNextPage
  const items = useMemo(() => pages?.flatMap((one) => one.items) ?? [], [pages])
  return {
    items,
    hasMore,
    showMore: () => {
      if (!hasMore || pages === undefined) return
      setRun({ question, start: pages.length })
      // A re-read under way gives way to the page asked for, which the next return to the window reads again
      void fetchNextPage()
    },
    isLoadingMore: asked.isFetchingNextPage,
    atEnd: own && last !== undefined && last.nextCursor === null,
    isLoaded: asked.data !== undefined,
    isStale: asked.isPlaceholderData,
    isPending: asked.isPending,
    isError: asked.isError,
    error: asked.error,
    refetch: () => void asked.refetch(),
  }
}
