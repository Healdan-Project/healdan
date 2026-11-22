// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { adminComponents } from '@healdan/api'
import { formatCount, formatDateTime, type TableColumn, type TableRow } from '@healdan/ui'
import { t } from '../strings'
import { areaWords } from './errorText'
import styles from './Monitoring.module.css'

type A = adminComponents['schemas']

/** A row of the Errors list: a kind of fault, keyed by its id */
export interface ErrorTableRow extends TableRow {
  fault: A['ErrorRow']
}

export function errorRowOf(fault: A['ErrorRow']): ErrorTableRow {
  return { id: String(fault.id), textValue: fault.message, fault }
}

/**
 * The Errors list's columns (spec 8.8): What Happened, Area, Times, First Seen and Last Seen, each plain text, as the
 * server orders them (the one last seen first), so none sorts. What Happened is the latest message, as logged, in one
 * line cut short; a resolved fault says so after it.
 */
export function errorColumns(): TableColumn<ErrorTableRow>[] {
  return [
    {
      id: 'message',
      name: t('admin.errors.column.message'),
      isRowHeader: true,
      width: '4fr',
      minWidth: 220,
      cell: ({ fault }) => (
        <span className={styles.cellMessage}>
          <span className={styles.cellText}>{fault.message}</span>
          {fault.resolved && (
            <>
              {' '}
              <span className={styles.tag}>{t('admin.errors.resolvedTag')}</span>
            </>
          )}
        </span>
      ),
    },
    { id: 'area', name: t('admin.errors.column.area'), width: '1fr', minWidth: 110, cell: ({ fault }) => <span className={styles.cellText}>{areaWords(fault.area)}</span> },
    { id: 'times', name: t('admin.errors.column.times'), align: 'end', width: 80, cell: ({ fault }) => formatCount(fault.times) },
    { id: 'firstSeen', name: t('admin.errors.column.firstSeen'), width: '1fr', minWidth: 140, cell: ({ fault }) => <span className={styles.cellText}>{formatDateTime(fault.firstSeen)}</span> },
    { id: 'lastSeen', name: t('admin.errors.column.lastSeen'), width: '1fr', minWidth: 140, cell: ({ fault }) => <span className={styles.cellText}>{formatDateTime(fault.lastSeen)}</span> },
  ]
}
