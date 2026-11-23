// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query'
import { useCallback, useEffect, useRef, useState } from 'react'
import { ApiError, apiPath, type Api, type adminComponents } from '@healdan/api'
import { ASK_ON_RETURN, useApi } from '@healdan/shell'
import { useCursorList, type CursorListing } from '../lists/useCursorList'
import { usePagedList, type PagedListing } from '../lists/usePagedList'
import { jobKeys, SOON_MS } from '../monitoring/jobQueries'
import { adminKey, adminKeys, POLL_MS } from '../queries'

type A = adminComponents['schemas']

/** How often the reindexes waiting or running are asked for again while one is, and the page is visible (P6-R19) */
export const REINDEX_POLL_MS = 5_000

/** The documents that couldn't be indexed, and those waiting, come 100 at a time, as the Errors list's faults do */
export const INDEX_PAGE = 100

/** The index's events, 100 a page by cursor, as the Audit Trail's rows */
export const INDEX_EVENTS_PAGE = 100

/** The reindexes on the History tab, 20 at a time */
export const REINDEX_HISTORY_PAGE = 20

/** At most this many reindexes waiting or running are listed above the tabs: one runs at a time, and few wait */
export const ACTIVE_LIMIT = 200

/**
 * The keys of what the Search Index asks for. Under `searchIndex`: the heading (that key itself), the documents that
 * couldn't be indexed, those waiting and the index's events, so invalidating it asks for all of them again. Under
 * `reindex`: the reindexes waiting or running, the History tab's reindexes, and each reindex by its id.
 */
export const indexKeys = {
  searchIndex: adminKey('/v1/admin/search-index'),
  reindex: adminKey('/v1/admin/reindex'),
  active: adminKey('/v1/admin/reindex', { active: true }),
  job: (id: number) => [...adminKey('/v1/admin/reindex'), id] as const,
}

/** The ended states of a reindex: it has stopped, done or cancelled, and is no longer among those waiting or running */
export function hasEnded(job: A['ReindexJob']): boolean {
  return job.state === 'done' || job.state === 'cancelled'
}

/** How often the heading is asked for again while the Indexer runs, and the page is visible (P6-R19, amended) */
export const INDEXER_POLL_MS = 5_000

/**
 * Whether the heading says the Indexer runs: its job running, or, just after a Run Now, its reason saying so while the job
 * still could run, until the run shows (the contract's `runRefusal`). An Indexer that can't run for another reason, as
 * one disabled, isn't running.
 */
export function indexerRunning(index: A['SearchIndex'] | undefined): boolean {
  if (index === undefined) return false
  const { job, runRefusal } = index.indexer
  return job.running || (runRefusal !== null && job.canRun)
}

/**
 * The Search Index's heading (spec 8.9, P6-R14): the counts, the index's entries and size, its health and the Indexer.
 * While the page is visible (P6-R19, amended), it asks again every 5 seconds while the Indexer runs, else every 30 while
 * `poll` says a reindex waits or runs. `askedAt`, when Run Indexer Now was last answered, has it ask again SOON_MS later,
 * as the answer may not show the run yet, until a read sent after that answer has ended, as Background Jobs does after a
 * Run Now. Each return to the window asks again however fresh it is.
 */
export function useSearchIndex(poll: boolean, askedAt?: number) {
  const api = useApi()
  // The `askedAt` the last read that ended was sent under
  const answeredFor = useRef<number | undefined>(undefined)
  return useQuery({
    queryKey: indexKeys.searchIndex,
    queryFn: async ({ signal }) => {
      const sentFor = askedAt
      try {
        const index = await api.get<A['SearchIndex']>('/v1/admin/search-index', undefined, { signal })
        if (!signal.aborted) answeredFor.current = sentFor
        return index
      } catch (error) {
        if (!signal.aborted) answeredFor.current = sentFor
        throw error
      }
    },
    refetchInterval: (query) => {
      if (askedAt !== undefined && answeredFor.current !== askedAt) return SOON_MS
      if (indexerRunning(query.state.data)) return INDEXER_POLL_MS
      return poll ? POLL_MS : false
    },
    refetchIntervalInBackground: false,
    meta: ASK_ON_RETURN,
  })
}

/**
 * The reindexes waiting or running, for the cards above the tabs (P6-R13): the running one first, then those waiting, in
 * the order they take their turns. While any is, it asks again every 5 seconds while the page is visible (P6-R19), and
 * otherwise at each return to the window.
 */
export function useActiveReindexes() {
  const api = useApi()
  return useQuery({
    queryKey: indexKeys.active,
    queryFn: async ({ signal }) => {
      const page = await api.get<A['ReindexJobList']>('/v1/admin/reindex', { active: 'true', limit: ACTIVE_LIMIT }, { signal })
      return inTurn(page.items ?? [])
    },
    refetchInterval: (query) => ((query.state.data?.length ?? 0) > 0 ? REINDEX_POLL_MS : false),
    refetchIntervalInBackground: false,
    meta: ASK_ON_RETURN,
  })
}

/** The running reindex first, then those waiting, oldest first, as their turns come */
function inTurn(jobs: A['ReindexJob'][]): A['ReindexJob'][] {
  const rank = (job: A['ReindexJob']) => (job.state === 'running' ? 0 : 1)
  return [...jobs].filter((job) => !hasEnded(job)).sort((a, b) => rank(a) - rank(b) || a.id - b.id)
}

/** The documents that couldn't be indexed, INDEX_PAGE at a time, the latest error first; `poll` as useSearchIndex's */
export function useIndexFailures(poll: boolean): PagedListing<A['IndexFailure']> {
  return usePagedList('/v1/admin/search-index/failed', {}, INDEX_PAGE, {}, { poll })
}

/** The documents waiting to be indexed, INDEX_PAGE at a time, in the order they were filed; `poll` as useSearchIndex's */
export function useIndexWaiting(poll: boolean): PagedListing<A['IndexWaiting']> {
  return usePagedList('/v1/admin/search-index/waiting', {}, INDEX_PAGE, {}, { poll })
}

/** Every reindex, the newest first, for the History tab; `poll` as useSearchIndex's */
export function useReindexHistory(poll: boolean): PagedListing<A['ReindexJob']> {
  return usePagedList('/v1/admin/reindex', {}, REINDEX_HISTORY_PAGE, {}, { poll })
}

/** The index's events, newest first, by cursor, for the History tab (P6-R13); `poll` as useSearchIndex's */
export function useIndexEvents(poll: boolean): CursorListing<A['IndexEvent'], A['IndexHistoryPage']> {
  return useCursorList('/v1/admin/search-index/history', {}, INDEX_EVENTS_PAGE, { poll })
}

/** Run Indexer Now (P6-R12): the Indexer as the server then has it, which may not show the run yet */
export function runIndexer(api: Api): Promise<A['Indexer']> {
  return api.send<A['Indexer']>('POST', '/v1/admin/search-index/run')
}

/** Try Again (P6-R11): how it went, indexed or still not */
export function retryIndexing(api: Api, docNumber: number): Promise<A['IndexRetry']> {
  return api.send<A['IndexRetry']>('POST', apiPath`/v1/admin/search-index/failed/${docNumber}/retry`)
}

/** Skip (P6-R11): the document set not to be indexed */
export async function skipIndexing(api: Api, docNumber: number): Promise<void> {
  await api.send<unknown>('POST', apiPath`/v1/admin/search-index/failed/${docNumber}/skip`)
}

/** Cancel (P6-R8): the reindex as it then is, cancelled, or still running and asked to stop */
export function cancelReindex(api: Api, id: number): Promise<A['ReindexJob']> {
  return api.send<A['ReindexJob']>('DELETE', apiPath`/v1/admin/reindex/${id}`)
}

/** A refusal that says what was shown is out of date: the document, or the reindex, is no longer as it was (`409`), or gone (`404`) */
export function isOutOfDate(error: unknown): boolean {
  return error instanceof ApiError && (error.status === 409 || error.status === 404)
}

/**
 * After what changes the index's counts: Try Again, Skip, Run Indexer Now and a reindex's end (P6-R15, P6-R19). The
 * heading, the tabs' lists and the reindexes are asked again at once, and the Dashboard's answer too, whose Needs
 * Attention and the left pane's Search Index count count the documents that couldn't be indexed. Nothing is waited for.
 */
export function indexChanged(queryClient: QueryClient): void {
  void queryClient.invalidateQueries({ queryKey: indexKeys.searchIndex })
  void queryClient.invalidateQueries({ queryKey: indexKeys.reindex })
  void queryClient.invalidateQueries({ queryKey: adminKeys.dashboard })
}

/**
 * After a refusal that says a row of Couldn't Be Indexed is out of date (P6-R11's `409`, or a `404`): the heading's counts
 * and the Dashboard read again, and nothing else, so the list keeps the row, and the refusal said in it, until the list is
 * next read, by its poll, a return to the window or a later change. Nothing is waited for.
 */
export function countsStale(queryClient: QueryClient): void {
  void queryClient.invalidateQueries({ queryKey: indexKeys.searchIndex, exact: true })
  void queryClient.invalidateQueries({ queryKey: adminKeys.dashboard })
}

/**
 * After Run Indexer Now: the Indexer as the server answered, in the heading in place of what it had (a read on its way is
 * cancelled first, so an older answer can't overwrite it), then what indexChanged asks for, and Background Jobs' list,
 * which lists the Indexer too.
 */
export async function indexerAnswered(queryClient: QueryClient, indexer: A['Indexer']): Promise<void> {
  await queryClient.cancelQueries({ queryKey: indexKeys.searchIndex, exact: true })
  queryClient.setQueryData<A['SearchIndex']>(indexKeys.searchIndex, (index) => (index === undefined ? index : { ...index, indexer }))
  indexChanged(queryClient)
  void queryClient.invalidateQueries({ queryKey: jobKeys.jobs, exact: true })
}

/**
 * After a reindex was started (the Reindex dialog's) or cancelled: the reindex as the server answered, in the list of those
 * waiting or running (added, put in place of what it had, or taken out once it has ended), then every reindex list asked
 * again, and Background Jobs' list and the Dashboard, which show the reindex job. A read on its way is cancelled first, so
 * an older answer can't overwrite it.
 */
export async function reindexChanged(queryClient: QueryClient, job: A['ReindexJob']): Promise<void> {
  await queryClient.cancelQueries({ queryKey: indexKeys.active, exact: true })
  queryClient.setQueryData<A['ReindexJob'][]>(indexKeys.active, (jobs = []) => {
    const others = jobs.filter((one) => one.id !== job.id)
    return inTurn(hasEnded(job) ? others : [...others, job])
  })
  void queryClient.invalidateQueries({ queryKey: indexKeys.reindex })
  // Background Jobs lists the reindex job, and the Dashboard's Background Jobs tile follows it
  void queryClient.invalidateQueries({ queryKey: jobKeys.jobs, exact: true })
  void queryClient.invalidateQueries({ queryKey: adminKeys.dashboard })
}

/**
 * After the heading shows the Indexer's run has ended: the tabs' lists and the Dashboard asked again at once, as the run
 * changed what waits and what couldn't be indexed. The heading itself, just read, isn't. Nothing is waited for.
 */
export function indexerEnded(queryClient: QueryClient): void {
  const heading = indexKeys.searchIndex
  void queryClient.invalidateQueries({ queryKey: heading, predicate: (query) => query.queryKey.length > heading.length })
  void queryClient.invalidateQueries({ queryKey: adminKeys.dashboard })
}

export interface ReindexEnds {
  /** The reindexes that ended while the page was open, the latest first, each as it ended, until it's closed */
  ended: A['ReindexJob'][]
  /** Close: the card of a reindex that ended goes */
  close: (id: number) => void
  /** A reindex the page saw end itself, as a waiting one Cancel cancelled at once */
  add: (job: A['ReindexJob']) => void
}

/**
 * The reindexes that end while the page is open (P6-R19): each that leaves the list of those waiting or running is asked
 * for once by its id, and kept as it ended (done or cancelled) for its card, until it's closed or the page is left; and
 * the counts, the tabs and the Dashboard are asked again at once, as its end changes them. One the page added itself, as
 * Cancel's answer, isn't asked for again. One that can't be read any more is left out.
 */
export function useReindexEnds(active: A['ReindexJob'][] | undefined): ReindexEnds {
  const api = useApi()
  const queryClient = useQueryClient()
  const [ended, setEnded] = useState<A['ReindexJob'][]>([])
  const seen = useRef<ReadonlySet<number> | undefined>(undefined)
  const known = useRef(new Set<number>())

  const add = useCallback((job: A['ReindexJob']) => {
    known.current.add(job.id)
    setEnded((jobs) => [job, ...jobs.filter((one) => one.id !== job.id)])
  }, [])

  useEffect(() => {
    if (active === undefined) return
    const before = seen.current
    const now = new Set(active.map((job) => job.id))
    seen.current = now
    if (before === undefined) return
    const gone = [...before].filter((id) => !now.has(id))
    if (gone.length === 0) return
    indexChanged(queryClient)
    for (const id of gone) {
      if (known.current.has(id)) continue
      known.current.add(id)
      api.get<A['ReindexJob']>(apiPath`/v1/admin/reindex/${id}`).then(
        (job) => {
          if (hasEnded(job)) add(job)
        },
        // Gone, or not read: the card goes with it, as the History tab keeps every reindex that ended
        () => undefined,
      )
    }
  }, [active, api, queryClient, add])

  const close = useCallback((id: number) => setEnded((jobs) => jobs.filter((job) => job.id !== id)), [])
  return { ended, close, add }
}
