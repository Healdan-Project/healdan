// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQueryClient } from '@tanstack/react-query'
import { useId, useRef, useState, type RefObject } from 'react'
import type { adminComponents } from '@healdan/api'
import { ErrorPanel, useApi } from '@healdan/shell'
import { Button, formatDateTime, ProgressBar, toast } from '@healdan/ui'
import { Confirm } from '../dialogs/Confirm'
import { useKeptFocus } from '../monitoring/useKeptFocus'
import { StaleNote } from '../people/StaleNote'
import { plural, t } from '../strings'
import { cancelReindex, hasEnded, isOutOfDate, indexKeys, reindexChanged, useActiveReindexes, type ReindexEnds } from './queries'
import { jobTitle, outcomeWords, startedWords, timeLeftWords, unreachableWords } from './text'
import styles from './SearchIndex.module.css'

type A = adminComponents['schemas']

/**
 * What's said under a running reindex's bar: how far, and how many of those on storage that couldn't be reached (P6-R23);
 * then how long is left, or that it's stopping, that the server's worker fails at each try, or that it waits for the index
 */
function runningDetail(job: A['ReindexJob']): string {
  const parts = [plural('admin.searchIndex.job.ofDocuments', job.total, { value: job.done + job.failed + job.skipped })]
  const unreachable = unreachableWords(job)
  if (unreachable !== undefined) parts.push(unreachable)
  if (job.cancelling) parts.push(t('admin.searchIndex.job.stopping'))
  else if (job.failingSince !== null) parts.push(t('admin.searchIndex.job.failing', { when: formatDateTime(job.failingSince) }))
  else if (job.stalledSince !== null) parts.push(t('admin.searchIndex.job.stalled', { when: formatDateTime(job.stalledSince) }))
  else {
    if (job.estimateSecondsLeft !== null) parts.push(timeLeftWords(job.estimateSecondsLeft))
    parts.push(t('admin.searchIndex.job.searchWorks'))
  }
  return parts.join(' · ')
}

/** How an ended reindex went: what it did, or, for one cancelled before its turn came, that it never started */
function endedWords(job: A['ReindexJob']): string {
  if (job.state !== 'cancelled') return t('admin.searchIndex.job.doneOutcome', { outcome: outcomeWords(job) })
  return job.started === null ? t('admin.searchIndex.job.cancelledBeforeStart') : t('admin.searchIndex.job.cancelledOutcome', { outcome: outcomeWords(job) })
}

interface CardProps {
  job: A['ReindexJob']
  /** Cancel, which asks first; none for a reindex that has ended or was asked to stop */
  onCancel: (job: A['ReindexJob']) => void
  /** Close, for a reindex that has ended */
  onClose: (job: A['ReindexJob']) => void
}

/**
 * A reindex's card (spec 8.9): its title, who started it and when, and, by its state, a running one's progress (`ProgressBar`,
 * done, failed and skipped together of its total) with Cancel, "Waiting for the index" while the index can't be written
 * (P6-R5), that it makes no progress while the server's worker fails, and that it's stopping once asked to; a waiting one's place in the queue with Cancel; and an ended one's outcome
 * with Close. Its title takes the focus when the button that had it goes.
 */
export function ReindexJobCard({ job, onCancel, onClose }: CardProps) {
  const titleId = useId()
  const title = jobTitle(job)
  const ended = hasEnded(job)
  return (
    <section className={styles.card} aria-labelledby={titleId} data-reindex={job.id}>
      <div className={styles.cardTop}>
        <h2 id={titleId} tabIndex={-1} className={styles.cardTitle} data-name>
          {title}
        </h2>
        <span className={styles.muted}>{startedWords(job)}</span>
        <div className={styles.cardActions}>
          {!ended && !job.cancelling && (
            <Button aria-label={t('admin.searchIndex.job.cancelName', { title })} onPress={() => onCancel(job)} data-action="cancel">
              {t('admin.searchIndex.job.cancel')}
            </Button>
          )}
          {ended && (
            <Button aria-label={t('admin.searchIndex.job.closeName', { title })} onPress={() => onClose(job)} data-action="close">
              {t('admin.searchIndex.job.close')}
            </Button>
          )}
        </div>
      </div>
      {job.state === 'running' && (
        <ProgressBar label={t('admin.searchIndex.job.documents')} value={job.done + job.failed + job.skipped} total={job.total} detail={runningDetail(job)} />
      )}
      {job.state === 'waiting' && <p className={styles.line}>{t('admin.searchIndex.job.turn', { documents: plural('admin.searchIndex.documents', job.total) })}</p>}
      {ended && (
        <p className={styles.line}>
          {endedWords(job)}
          {job.failed > 0 && ` ${t('admin.searchIndex.job.failedJoin')}`}
        </p>
      )}
    </section>
  )
}

export interface ReindexJobsProps {
  /** What the page saw end, and Close */
  ends: ReindexEnds
  /** Where the focus goes when a card it was on goes and no other is there: the page's heading */
  fallback: RefObject<HTMLElement | null>
}

/**
 * The reindexes above the tabs (spec 8.9, P6-R13): the running one, then those waiting their turn, and those that ended
 * while the page was open, each where it was until it's closed. Cancel asks first, naming the reindex and what it keeps; the answer is the
 * reindex as it then is, which its card shows, and a refusal stays in the question, the cards read again first when it says
 * they're out of date. While a new read fails, the cards it had stay, with a note (P4-R15).
 */
export function ReindexJobs({ ends, fallback }: ReindexJobsProps) {
  const api = useApi()
  const queryClient = useQueryClient()
  const active = useActiveReindexes()
  const box = useRef<HTMLDivElement>(null)
  const [asking, setAsking] = useState<A['ReindexJob']>()
  useKeptFocus(box, fallback, { attribute: 'reindex', name: '[data-name]', toNextRow: true })

  async function cancel(job: A['ReindexJob']) {
    let answer
    try {
      answer = await cancelReindex(api, job.id)
    } catch (error) {
      if (isOutOfDate(error)) await queryClient.refetchQueries({ queryKey: indexKeys.active, exact: true })
      throw error
    }
    if (hasEnded(answer)) ends.add(answer)
    await reindexChanged(queryClient, answer)
    toast(t(hasEnded(answer) ? 'admin.searchIndex.job.cancelledToast' : 'admin.searchIndex.job.stoppingToast', { name: answer.name }))
  }

  // In the order the reindexes take their turns, oldest first, which puts the running one first, as it's always the oldest
  // active one (P6-R3): so each card keeps its place as it ends, among the others, and one started later comes after them
  const shown = new Map<number, A['ReindexJob']>([...(active.data ?? []), ...ends.ended].map((job) => [job.id, job]))
  const jobs = [...shown.values()].sort((a, b) => a.id - b.id)
  return (
    <>
      <StaleNote failed={active.isError && active.data !== undefined} onRetry={() => void active.refetch()} />
      {active.isError && active.data === undefined && <ErrorPanel error={active.error} onRetry={() => void active.refetch()} />}
      <div ref={box} className={styles.cards}>
        {jobs.map((job) => (
          <ReindexJobCard key={job.id} job={job} onCancel={setAsking} onClose={(one) => ends.close(one.id)} />
        ))}
      </div>
      {asking !== undefined && (
        <Confirm
          title={t('admin.searchIndex.cancelTitle', { name: asking.name })}
          confirm={t('admin.searchIndex.cancelConfirm')}
          run={() => cancel(asking)}
          onClose={() => setAsking(undefined)}
        >
          <p className={styles.line}>{t(asking.state === 'waiting' ? 'admin.searchIndex.cancelWaiting' : 'admin.searchIndex.cancelRunning')}</p>
        </Confirm>
      )}
    </>
  )
}
