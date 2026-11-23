// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useId, useRef } from 'react'
import type { adminComponents } from '@healdan/api'
import { ErrorPanel } from '@healdan/shell'
import { Button, formatDateTime, formatCount } from '@healdan/ui'
import { useFocusWhenGone } from '../focus'
import { SidewaysBox } from '../lists/SidewaysBox'
import { StaleNote } from '../people/StaleNote'
import { t } from '../strings'
import { useIndexEvents, useReindexHistory } from './queries'
import { ShowMore } from './ShowMore'
import { docNumberHeader, matterWords, outcomeWords, stateWords } from './text'
import styles from './SearchIndex.module.css'

type A = adminComponents['schemas']

/** A reindex's outcome on the History tab: how it ended, and, once it has, how it went */
function outcomeCell(job: A['ReindexJob']): string {
  return job.state === 'done' || job.state === 'cancelled' || job.done + job.failed + job.skipped > 0 ? `${stateWords(job)}: ${outcomeWords(job)}` : stateWords(job)
}

/** The reindexes, the newest first, each with who started it, when, how many it tries and how it went */
function Reindexes({ poll }: { poll: boolean }) {
  const headingId = useId()
  const heading = useRef<HTMLHeadingElement>(null)
  const listing = useReindexHistory(poll)
  let body
  if (listing.isError && listing.total === undefined) body = <ErrorPanel error={listing.error} onRetry={listing.refetch} />
  else if (listing.items.length === 0) body = <p className={styles.muted}>{listing.isPending ? t('common.loading') : t('admin.searchIndex.history.noReindexes')}</p>
  else {
    body = (
      <>
        <SidewaysBox name={t('admin.searchIndex.sideways', { name: t('admin.searchIndex.history.reindexes') })} className={styles.tableBox}>
          <table className={styles.table} aria-labelledby={headingId}>
            <thead>
              <tr>
                <th scope="col">{t('admin.searchIndex.column.reindex')}</th>
                <th scope="col">{t('admin.searchIndex.column.startedBy')}</th>
                <th scope="col">{t('admin.searchIndex.column.started')}</th>
                <th scope="col">{t('admin.searchIndex.column.documents')}</th>
                <th scope="col">{t('admin.searchIndex.column.outcome')}</th>
              </tr>
            </thead>
            <tbody>
              {listing.items.map((job) => (
                <tr key={job.id}>
                  <th scope="row" className={`${styles.name} ${styles.wraps}`}>
                    {job.name}
                  </th>
                  <td>{job.startedBy.name}</td>
                  <td>
                    <time dateTime={job.created}>{formatDateTime(job.created)}</time>
                  </td>
                  <td>{formatCount(job.total)}</td>
                  <td className={styles.wraps}>{outcomeCell(job)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </SidewaysBox>
        <ShowMore listing={listing} fallback={heading} />
      </>
    )
  }
  return (
    <section aria-labelledby={headingId}>
      <h2 id={headingId} ref={heading} tabIndex={-1} className={styles.sectionTitle}>
        {t('admin.searchIndex.history.reindexes')}
      </h2>
      <StaleNote failed={listing.isError && listing.total !== undefined} onRetry={listing.refetch} />
      {body}
    </section>
  )
}

/**
 * The index's events, newest first, by cursor (P6-R13): each page reads only so far back, so it can come short, or empty,
 * with more to read; the list asks for a few such pages itself, then offers Show More, saying how far back it has read
 * (useCursorList, as the Audit Trail does). A row's reason is the server's, drawn as text.
 */
function Events({ poll }: { poll: boolean }) {
  const headingId = useId()
  const heading = useRef<HTMLHeadingElement>(null)
  const end = useRef<HTMLParagraphElement>(null)
  const listing = useIndexEvents(poll)
  const offering = listing.isLoaded && !listing.isStale && !listing.atEnd
  const following = offering && !listing.hasMore
  const readBackTo = listing.last?.readBackTo ?? null
  useFocusWhenGone(!offering && listing.isLoaded, listing.items.length > 0 ? end : heading)

  let body
  if (listing.isError && !listing.isLoaded) body = <ErrorPanel error={listing.error} onRetry={listing.refetch} />
  else if (listing.items.length === 0) {
    let none = t('common.loading')
    if (listing.atEnd) none = t('admin.searchIndex.history.noEvents')
    else if (listing.hasMore) none = readBackTo === null ? t('admin.searchIndex.history.noneYet') : t('admin.searchIndex.history.noneBackTo', { when: formatDateTime(readBackTo) })
    body = <p className={styles.muted}>{none}</p>
  } else {
    body = (
      <SidewaysBox name={t('admin.searchIndex.sideways', { name: t('admin.searchIndex.history.events') })} className={styles.tableBox}>
        <table className={styles.table} aria-labelledby={headingId}>
          <thead>
            <tr>
              <th scope="col">{t('admin.searchIndex.column.time')}</th>
              <th scope="col">{t('admin.searchIndex.column.docNumber')}</th>
              <th scope="col">{t('admin.searchIndex.column.title')}</th>
              <th scope="col">{t('admin.searchIndex.column.matter')}</th>
              <th scope="col">{t('admin.searchIndex.column.event')}</th>
              <th scope="col">{t('admin.searchIndex.column.reason')}</th>
              <th scope="col">{t('admin.searchIndex.column.who')}</th>
            </tr>
          </thead>
          <tbody>
            {listing.items.map((event) => (
              <tr key={event.id}>
                <th scope="row" className={styles.name}>
                  <time dateTime={event.when}>{formatDateTime(event.when)}</time>
                </th>
                <td>{docNumberHeader(event.document)}</td>
                <td className={styles.wraps}>{event.document.fileName}</td>
                <td>{matterWords(event.document)}</td>
                <td>{t(event.event === 'indexed' ? 'admin.searchIndex.event.indexed' : 'admin.searchIndex.event.failed')}</td>
                <td className={styles.wraps}>{event.reason ?? ''}</td>
                <td>{event.who ?? t('admin.audit.notRecorded')}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </SidewaysBox>
    )
  }

  return (
    <section aria-labelledby={headingId}>
      <h2 id={headingId} ref={heading} tabIndex={-1} className={styles.sectionTitle}>
        {t('admin.searchIndex.history.events')}
      </h2>
      <StaleNote failed={listing.isError && listing.isLoaded} onRetry={listing.refetch} />
      {body}
      <div className={styles.footer}>
        {offering && (
          <Button isPending={following} onPress={listing.showMore}>
            {t('admin.searchIndex.showMore')}
          </Button>
        )}
        {offering && listing.items.length > 0 && readBackTo !== null && <p className={styles.muted}>{t('admin.searchIndex.history.reach', { when: formatDateTime(readBackTo) })}</p>}
        {listing.atEnd && listing.items.length > 0 && (
          <p ref={end} tabIndex={-1} className={styles.muted}>
            {t('admin.searchIndex.history.end')}
          </p>
        )}
      </div>
    </section>
  )
}

/** The History tab (spec 8.9, P6-R13): the reindexes, then what the index was given */
export function IndexHistory({ poll }: { poll: boolean }) {
  return (
    <div className={styles.tab}>
      <Reindexes poll={poll} />
      <Events poll={poll} />
    </div>
  )
}
