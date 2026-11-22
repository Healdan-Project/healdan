// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { adminComponents } from '@healdan/api'
import { formatCount, type TableColumn, type TableRow } from '@healdan/ui'
import { t } from '../strings'
import { mappersOf } from './groupText'
import styles from './People.module.css'

type A = adminComponents['schemas']

/** A row of the Groups list: the group, keyed by its id */
export interface GroupTableRow extends TableRow {
  group: A['Group']
}

export function groupRowOf(group: A['Group']): GroupTableRow {
  return { id: String(group.id), textValue: group.name, group }
}

/** What's said after a group's name: Fixed, for a group that's never renamed or deleted, and the providers that map it */
function GroupName({ group }: { group: A['Group'] }) {
  return (
    <span className={styles.cellText}>
      {group.name}
      {group.fixed && (
        <>
          {' '}
          <span className={styles.tag}>{t('admin.groups.fixed')}</span>
        </>
      )}
      {group.mappedBy.length > 0 && (
        <>
          {' '}
          <span className={styles.tag}>{t('admin.groups.mapped', { providers: mappersOf(group) })}</span>
        </>
      )}
    </span>
  )
}

/**
 * The Groups list's columns (spec 8.7): Group, with what marks a fixed group and one a sign-in provider maps, Description
 * and Members. None sorts: the server lists groups by name.
 */
export function groupColumns(): TableColumn<GroupTableRow>[] {
  return [
    { id: 'name', name: t('admin.groups.column.name'), isRowHeader: true, width: '2fr', minWidth: 180, cell: ({ group }) => <GroupName group={group} /> },
    {
      id: 'description',
      name: t('admin.groups.column.description'),
      width: '3fr',
      minWidth: 160,
      cell: ({ group }) => <span className={`${styles.cellText} ${styles.muted}`}>{group.description ?? ''}</span>,
    },
    { id: 'members', name: t('admin.groups.column.members'), align: 'end', width: 96, cell: ({ group }) => formatCount(group.members) },
  ]
}
