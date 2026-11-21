// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { adminComponents } from '@healdan/api'
import { formatCount, formatDate, formatSize, Icon, type TableColumn, type TableRow } from '@healdan/ui'
import { t } from '../strings'
import type { MatterSort } from './useMatterList'
import styles from './Matters.module.css'

type A = adminComponents['schemas']

/** A row of a list of matters: the matter, keyed by its id */
export interface MatterTableRow extends TableRow {
  matter: A['MatterRow']
}

export function matterRowOf(matter: A['MatterRow']): MatterTableRow {
  return { id: String(matter.id), textValue: t('matter.label', { number: matter.number, name: matter.name }), matter }
}

/** Text that is secondary to the matter's own, muted, as the mockup's Client, Store, Owner, Template and Last Activity are */
function Muted({ children }: { children: string }) {
  return <span className={styles.muted}>{children}</span>
}

type Column = TableColumn<MatterTableRow> & { id: MatterSort }

/**
 * Every column of a list of matters (spec 8.3), each sorting by what the server sorts by under its id: Matter, Name,
 * Client, Store, Owner, Template, Documents, Size and Last Activity. A figure the summary hasn't given yet, an owner whose
 * account has gone and a template the matter wasn't made from are blank. Built when a list draws, so the firm's own word
 * for a matter (setLabels) is the heading; `leaveOut` drops the columns a list doesn't need, as a client's page has no
 * Client column.
 */
export function matterColumns(leaveOut: readonly MatterSort[] = []): TableColumn<MatterTableRow>[] {
  const columns: Column[] = [
    {
      id: 'number',
      name: t('admin.matters.column.number'),
      isRowHeader: true,
      width: 104,
      cell: ({ matter }) => (
        <span className={styles.number}>
          <Icon name="briefcase" kind="matter" />
          {matter.number}
        </span>
      ),
    },
    // The number and the name together name a row, for a screen reader
    { id: 'name', name: t('admin.matters.column.name'), isRowHeader: true, width: '3fr', minWidth: 160, cell: ({ matter }) => matter.name },
    { id: 'client', name: t('admin.matters.column.client'), width: '2fr', minWidth: 120, cell: ({ matter }) => <Muted>{matter.client.name}</Muted> },
    { id: 'store', name: t('admin.matters.column.store'), width: '1fr', minWidth: 96, cell: ({ matter }) => <Muted>{matter.store.name}</Muted> },
    { id: 'owner', name: t('admin.matters.column.owner'), width: '1.2fr', minWidth: 104, cell: ({ matter }) => <Muted>{matter.owner?.name ?? ''}</Muted> },
    { id: 'template', name: t('admin.matters.column.template'), width: '1fr', minWidth: 96, cell: ({ matter }) => <Muted>{matter.template?.name ?? ''}</Muted> },
    { id: 'documents', name: t('admin.matters.column.documents'), align: 'end', width: 96, cell: ({ matter }) => (matter.documents === null ? '' : formatCount(matter.documents)) },
    { id: 'bytes', name: t('admin.matters.column.bytes'), align: 'end', width: 84, cell: ({ matter }) => (matter.bytes === null ? '' : formatSize(matter.bytes)) },
    { id: 'lastActivity', name: t('admin.matters.column.lastActivity'), width: 120, cell: ({ matter }) => <Muted>{matter.lastActivity === null ? '' : formatDate(matter.lastActivity)}</Muted> },
  ]
  return columns.filter((column) => !leaveOut.includes(column.id)).map((column) => ({ ...column, allowsSorting: true }))
}
