// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { formatDate, t } from '@healdan/ui'
import { ErrorPanel } from '../frame/ErrorPanel'
import { useRecentDocuments } from '../queries'
import { useSelection } from '../selection'
import styles from './views.module.css'

// The start page: the documents the person worked on lately, in the order the server gives (newest first).
export function RecentDocuments() {
  const recent = useRecentDocuments()
  const { selected, select } = useSelection()
  const title = t('recent.heading.documents')
  return (
    <section className={styles.view}>
      <h1 className={styles.title}>{title}</h1>
      {recent.isError ? (
        <ErrorPanel error={recent.error} onRetry={() => void recent.refetch()} />
      ) : recent.data && recent.data.items.length === 0 ? (
        <p className={styles.empty}>{t('recent.empty.documents')}</p>
      ) : (
        <ul className={styles.list} aria-label={title}>
          {recent.data?.items.map((doc) => (
            <li key={doc.docNumber}>
              <button
                type="button"
                className={styles.row}
                aria-pressed={selected === doc.docNumber}
                onClick={() => select(selected === doc.docNumber ? undefined : doc.docNumber)}
              >
                <span className={styles.name}>{doc.name}</span>
                {doc.matter && <span className={styles.meta}>{doc.matter.display}</span>}
                <span className={styles.meta}>{formatDate(doc.lastModified)}</span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
