// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQuery, type QueryClient } from '@tanstack/react-query'
import { useRef } from 'react'
import { ApiError, apiPath, type Api, type adminComponents } from '@healdan/api'
import { ASK_ON_RETURN, useApi } from '@healdan/shell'
import { adminKey, adminKeys, POLL_MS } from '../queries'

type A = adminComponents['schemas']

/**
 * How soon the list asks again after Run Now or Stop: a run begins off the request's thread, so the answer may not show it
 * running yet, and a stop shows once the run reaches its next check
 */
export const SOON_MS = 2_000

/** What a row's button asks the server for, each answered with the job */
export type JobAction = 'run' | 'stop' | 'enable' | 'disable'

export const jobKeys = {
  jobs: adminKey('/v1/admin/jobs'),
}

/**
 * Every background job (spec 8.8, 9.5): a short list, so not a page. While any job is running it asks again every 30
 * seconds while the page is visible (spec 7.2; P5-R21), and nothing on a timer otherwise. `askedAt`, when a Run Now or a
 * Stop was last answered, has it ask again SOON_MS later, whatever else runs, as a Run Now's answer may not show the run
 * yet, and a Stop's shows it going on until its next check. Only a read sent after that answer ends the wait, once it's
 * answered or has failed: not another change's answer put in the list meanwhile, nor a read cancelled on its way, so the
 * wait goes on through both. Each return to the window asks again however fresh it is, as jobs start and end on their
 * own. A failed re-read keeps the last answer, and the poll goes on.
 */
export function useJobs(askedAt?: number) {
  const api = useApi()
  // The `askedAt` the last read that ended was sent under
  const answeredFor = useRef<number | undefined>(undefined)
  return useQuery({
    queryKey: jobKeys.jobs,
    queryFn: async ({ signal }) => {
      const sentFor = askedAt
      try {
        const jobs = await api.get<A['Job'][]>('/v1/admin/jobs', undefined, { signal })
        if (!signal.aborted) answeredFor.current = sentFor
        return jobs
      } catch (error) {
        if (!signal.aborted) answeredFor.current = sentFor
        throw error
      }
    },
    // TanStack Query asks on its interval only while its focusManager says the page is visible, which the shell's query
    // client tells it, and works the interval out again after each answer and each change of `askedAt`
    refetchInterval: (query) => {
      if (askedAt !== undefined && answeredFor.current !== askedAt) return SOON_MS
      return query.state.data?.some((job) => job.running) ? POLL_MS : false
    },
    refetchIntervalInBackground: false,
    meta: ASK_ON_RETURN,
  })
}

/** Run Now, Stop, Enable or Disable: the job as the server has it once asked */
export function jobAction(api: Api, name: string, action: JobAction): Promise<A['Job']> {
  switch (action) {
    case 'run':
      return api.send<A['Job']>('POST', apiPath`/v1/admin/jobs/${name}/run`)
    case 'stop':
      return api.send<A['Job']>('POST', apiPath`/v1/admin/jobs/${name}/stop`)
    case 'enable':
      return api.send<A['Job']>('POST', apiPath`/v1/admin/jobs/${name}/enable`)
    case 'disable':
      return api.send<A['Job']>('POST', apiPath`/v1/admin/jobs/${name}/disable`)
  }
}

/** A LogicalDOC task's new schedule: the job, with it, as the server then has it */
export function saveSchedule(api: Api, name: string, change: A['JobScheduleChange']): Promise<A['Job']> {
  return api.send<A['Job']>('PUT', apiPath`/v1/admin/jobs/${name}/schedule`, change)
}

/**
 * After an action the server refused as no longer allowed (`409`) or for a job it no longer has (`404`): the list read again
 * at once, and waited for, as the refusal says the list is out of date and nothing else may ask (nothing runs, so nothing
 * polls). The refusal is then said against the job as that read found it. A read that fails keeps the list as it was.
 */
export async function jobsStale(queryClient: QueryClient, refusal: unknown): Promise<void> {
  if (!(refusal instanceof ApiError) || (refusal.status !== 409 && refusal.status !== 404)) return
  await queryClient.refetchQueries({ queryKey: jobKeys.jobs, exact: true })
}

/** The job as the list last read it, or undefined once the list has none of that name */
export function jobIn(queryClient: QueryClient, name: string): A['Job'] | undefined {
  return queryClient.getQueryData<A['Job'][]>(jobKeys.jobs)?.find((job) => job.name === name)
}

/**
 * After an action: the job as the server answered, in the list in place of what it had, and the Dashboard's answer asked
 * again, whose Needs Attention and the left pane's Background Jobs count count the jobs that failed or are overdue
 * (P5-R20). A read of the list on its way is cancelled first, so an older answer can't overwrite the job. The Dashboard
 * isn't waited for: the change is done once the answer is in.
 */
export async function jobChanged(queryClient: QueryClient, job: A['Job']): Promise<void> {
  await queryClient.cancelQueries({ queryKey: jobKeys.jobs, exact: true })
  queryClient.setQueryData<A['Job'][]>(jobKeys.jobs, (jobs) => jobs?.map((one) => (one.name === job.name ? job : one)))
  void queryClient.invalidateQueries({ queryKey: adminKeys.dashboard })
}
