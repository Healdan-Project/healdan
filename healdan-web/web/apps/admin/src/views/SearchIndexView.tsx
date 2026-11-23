// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQueryClient } from '@tanstack/react-query'
import { useNavigate } from '@tanstack/react-router'
import { useId, useRef, useState } from 'react'
import type { adminComponents } from '@healdan/api'
import { ErrorPanel, useApi } from '@healdan/shell'
import { Button, formatCount, formatDateTime, formatSize, Icon, StatusDot, Tabs, toast } from '@healdan/ui'
import { routes, type IndexTab, type SearchIndexSearch } from '../addresses'
import { useFocusWhenGone } from '../focus'
import { Problems } from '../forms/Problems'
import { Refusal } from '../forms/Refusal'
import { FailedList } from '../index/FailedList'
import { IndexHistory } from '../index/IndexHistory'
import { indexChanged, indexerAnswered, isOutOfDate, runIndexer, useActiveReindexes, useReindexEnds, useSearchIndex } from '../index/queries'
import { ReindexJobs } from '../index/ReindexJobCard'
import { WaitingList } from '../index/WaitingList'
import { StaleNote } from '../people/StaleNote'
import { plural, t } from '../strings'
import styles from '../index/SearchIndex.module.css'

type A = adminComponents['schemas']

/** A tab's label, with its count once the heading has it: "Couldn't Be Indexed (2)" */
function counted(label: string, count: number | undefined): string {
  return count === undefined ? label : t('admin.searchIndex.tabCount', { label, count: formatCount(count) })
}

/** The Indexer's last and next run, or that it's running, on one line */
function indexerLine(job: A['Job']): string {
  if (job.running) return job.lastStart === null ? t('admin.searchIndex.indexer.running') : t('admin.searchIndex.indexer.runningSince', { when: formatDateTime(job.lastStart) })
  const last = job.lastStart === null ? t('admin.searchIndex.indexer.never') : t('admin.searchIndex.indexer.last', { when: formatDateTime(job.lastStart) })
  const next = job.nextRun === null ? t('admin.searchIndex.indexer.noNext') : t('admin.searchIndex.indexer.next', { when: formatDateTime(job.nextRun) })
  return `${last} · ${next}`
}

/** What's under Indexed: the index's entries and its folder's size, each where the server could read it */
function indexedDetail(index: A['SearchIndex']): string | undefined {
  const parts = [index.entries === null ? undefined : plural('admin.searchIndex.entries', index.entries), index.bytes === null ? undefined : formatSize(index.bytes)]
  const known = parts.filter((part): part is string => part !== undefined)
  return known.length === 0 ? undefined : known.join(' · ')
}

/** The heading's figures: Indexed, Waiting and Couldn't Be Indexed, then the index's health and the Indexer's runs */
function Figures({ index }: { index: A['SearchIndex'] }) {
  const detail = indexedDetail(index)
  return (
    <ul className={styles.tiles}>
      <li className={styles.tile}>
        <span className={styles.big}>{formatCount(index.indexed)}</span>
        <span className={styles.muted}>{t('admin.searchIndex.indexed')}</span>
        {detail !== undefined && <span className={styles.small}>{detail}</span>}
      </li>
      <li className={styles.tile}>
        <span className={styles.big}>{formatCount(index.waiting)}</span>
        <span className={styles.muted}>{t('admin.searchIndex.waiting')}</span>
      </li>
      <li className={styles.tile}>
        <span className={index.failed > 0 ? `${styles.big} ${styles.caution}` : styles.big}>{formatCount(index.failed)}</span>
        <span className={styles.muted}>{t('admin.searchIndex.failed')}</span>
      </li>
      <li className={styles.tile}>
        <StatusDot status={index.health.status}>{t(index.health.status === 'ok' ? 'admin.searchIndex.healthy' : 'admin.searchIndex.attention')}</StatusDot>
        <span className={styles.small}>{indexerLine(index.indexer.job)}</span>
      </li>
    </ul>
  )
}

/**
 * The Search Index, at /admin/search-index (spec 8.9): the counts of documents indexed, waiting and that couldn't be
 * indexed, with the index's entries and size, its health and the Indexer's last and next run; Run Indexer Now, greyed out
 * with the server's reason while it can't run (P6-R12); Reindex…, which opens the Reindex dialog by the address (P6-R18);
 * the reindexes waiting or running, and those that ended while the page was open, above the tabs; and the tabs, Couldn't Be
 * Indexed, Waiting and History, the one open in the address. While a reindex waits or runs, the reindexes are asked for
 * every 5 seconds and the counts and the open tab every 30, while the page is visible; otherwise at each return to the
 * window (P6-R19). A reindex's end, Try Again, Skip and Run Indexer Now ask for the counts, the tabs and the Dashboard
 * again at once. Each part keeps its last answer while a new read fails, with a note (P4-R15).
 */
export function SearchIndexView({ search }: { search: SearchIndexSearch }) {
  const api = useApi()
  const queryClient = useQueryClient()
  const navigate = useNavigate({ from: routes.searchIndex })
  const refusalId = useId()
  const heading = useRef<HTMLHeadingElement>(null)
  const active = useActiveReindexes()
  const running = (active.data?.length ?? 0) > 0
  const ends = useReindexEnds(active.data)
  const index = useSearchIndex(running)
  const [busy, setBusy] = useState(false)
  const [failure, setFailure] = useState<unknown>()
  const tab: IndexTab = search.tab ?? 'failed'

  async function run() {
    setBusy(true)
    setFailure(undefined)
    try {
      await indexerAnswered(queryClient, await runIndexer(api))
      toast(t('admin.searchIndex.runAsked'))
    } catch (error) {
      setFailure(error)
      // Refused as the Indexer has changed since: the heading read again shows it as it is
      if (isOutOfDate(error)) indexChanged(queryClient)
    } finally {
      setBusy(false)
    }
  }

  const data = index.data
  // Run Indexer Now follows the server's reason, which can say it's running a moment before the job shows it
  const refusal = data?.indexer.runRefusal ?? null
  // Greyed out under the focus, as once a run it was asked for shows, the button gives the focus to the page's heading
  useFocusWhenGone(refusal !== null, heading)

  return (
    <div className={styles.view}>
      <div className={styles.head}>
        <h1 ref={heading} tabIndex={-1} className={styles.title}>
          {t('admin.searchIndex.title')}
        </h1>
        <div className={styles.headActions}>
          <Button isDisabled={data === undefined || refusal !== null} aria-describedby={refusal === null ? undefined : refusalId} isPending={busy} onPress={() => void run()}>
            <span className={styles.withIcon}>
              <Icon name="clockPlay" />
              {t('admin.searchIndex.run')}
            </span>
          </Button>
          <Button variant="default" onPress={() => void navigate({ to: routes.searchIndex, search: (prev) => ({ ...prev, reindex: 'matter' }) })}>
            <span className={styles.withIcon}>
              <Icon name="refresh" />
              {t('admin.searchIndex.reindex')}
            </span>
          </Button>
        </div>
      </div>
      {refusal !== null && (
        <div className={styles.refused}>
          <Problems problems={[{ key: 'run', id: refusalId, sentence: refusal }]} />
        </div>
      )}
      <Refusal failure={failure} />
      <StaleNote failed={index.isError && data !== undefined} onRetry={() => void index.refetch()} />
      {data === undefined ? (
        index.isError ? (
          <ErrorPanel error={index.error} onRetry={() => void index.refetch()} />
        ) : (
          <p className={styles.muted}>{t('common.loading')}</p>
        )
      ) : (
        <Figures index={data} />
      )}
      <ReindexJobs ends={ends} fallback={heading} />
      <Tabs
        label={t('admin.searchIndex.tabs')}
        selected={tab}
        // A tab isn't a step of its own; the dialog's parameters stay as they are
        onSelect={(next) => void navigate({ to: routes.searchIndex, search: (prev) => ({ ...prev, tab: next === 'waiting' || next === 'history' ? next : undefined }), replace: true })}
        tabs={[
          { id: 'failed', label: counted(t('admin.searchIndex.tab.failed'), data?.failed), content: <FailedList poll={running} /> },
          { id: 'waiting', label: counted(t('admin.searchIndex.tab.waiting'), data?.waiting), content: <WaitingList poll={running} /> },
          { id: 'history', label: t('admin.searchIndex.tab.history'), content: <IndexHistory poll={running} /> },
        ]}
      />
    </div>
  )
}
