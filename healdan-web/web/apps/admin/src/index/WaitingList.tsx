// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useRef } from 'react'
import { ErrorPanel } from '@healdan/shell'
import { formatDateTime } from '@healdan/ui'
import { StaleNote } from '../people/StaleNote'
import { t } from '../strings'
import { useIndexWaiting } from './queries'
import { ShowMore } from './ShowMore'
import { docNumberHeader, matterWords } from './text'
import styles from './SearchIndex.module.css'

/**
 * The Waiting tab (spec 8.9): each document waiting for LogicalDOC's Indexer, those that couldn't be indexed left out, in
 * the order they were filed, with its doc #, title, matter and when it last changed (the contract's `lastChanged`, not when
 * it began to wait, which nothing records). A plain table, as the other tabs', a page at a time with Show More. While a new
 * read fails, the rows it had stay, with a note (P4-R15).
 */
export function WaitingList({ poll }: { poll: boolean }) {
  const listing = useIndexWaiting(poll)
  const about = useRef<HTMLParagraphElement>(null)
  let body
  if (listing.isError && listing.total === undefined) body = <ErrorPanel error={listing.error} onRetry={listing.refetch} />
  else if (listing.items.length === 0) body = <p className={styles.muted}>{listing.isPending ? t('common.loading') : t('admin.searchIndex.waiting.none')}</p>
  else {
    body = (
      <>
        <div className={styles.tableBox}>
          <table className={styles.table} aria-label={t('admin.searchIndex.tab.waiting')}>
            <thead>
              <tr>
                <th scope="col">{t('admin.searchIndex.column.docNumber')}</th>
                <th scope="col">{t('admin.searchIndex.column.title')}</th>
                <th scope="col">{t('admin.searchIndex.column.matter')}</th>
                <th scope="col">{t('admin.searchIndex.column.lastChanged')}</th>
              </tr>
            </thead>
            <tbody>
              {listing.items.map(({ document, lastChanged }) => (
                <tr key={document.id}>
                  <th scope="row" className={styles.name}>
                    {docNumberHeader(document)}
                  </th>
                  <td className={styles.wraps}>{document.fileName}</td>
                  <td>{matterWords(document)}</td>
                  <td>
                    <time dateTime={lastChanged}>{formatDateTime(lastChanged)}</time>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <ShowMore listing={listing} fallback={about} />
      </>
    )
  }
  return (
    <div className={styles.tab}>
      <p ref={about} tabIndex={-1} className={styles.about}>
        {t('admin.searchIndex.waiting.about')}
      </p>
      <StaleNote failed={listing.isError && listing.total !== undefined} onRetry={listing.refetch} />
      {body}
    </div>
  )
}
