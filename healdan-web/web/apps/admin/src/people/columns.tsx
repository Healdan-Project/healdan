// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { adminComponents } from '@healdan/api'
import { formatCount, formatDateTime, StatusDot, type TableColumn, type TableRow } from '@healdan/ui'
import { t } from '../strings'
import { EVERYONE, signsInWithWords, statusDot, statusWords } from './text'
import styles from './People.module.css'

type A = adminComponents['schemas']

/** A row of the Users list: the person, keyed by their id */
export interface UserTableRow extends TableRow {
  user: A['UserRow']
}

export function userRowOf(user: A['UserRow']): UserTableRow {
  return { id: String(user.id), textValue: user.name, user }
}

/**
 * The groups a row names: those the person is in but Everyone, which every account but a read-only one is in, and so
 * says nothing of anyone, as the list's drawing has it
 */
function groupsOf(user: A['UserRow']): string {
  return user.groups
    .filter((group) => group.name !== EVERYONE)
    .map((group) => group.name)
    .join(', ')
}

/**
 * The Users list's columns (spec 8.7): Name, Username, Groups, Signs In With, Status, Last Sign-In and Devices. Name,
 * Username and Last Sign-In sort, by what the server sorts by under their ids; Last Sign-In newest first at first, and
 * someone who has never signed in says so, last whichever the order.
 */
export function userColumns(): TableColumn<UserTableRow>[] {
  return [
    {
      id: 'name',
      name: t('admin.users.column.name'),
      isRowHeader: true,
      allowsSorting: true,
      width: '2fr',
      minWidth: 160,
      cell: ({ user }) => (
        <span className={styles.cellText}>
          {user.name}
          {user.readOnly && (
            <>
              {' '}
              <span className={styles.tag}>{t('admin.users.readOnly')}</span>
            </>
          )}
        </span>
      ),
    },
    { id: 'username', name: t('admin.users.column.username'), allowsSorting: true, width: '1fr', minWidth: 110, cell: ({ user }) => <span className={styles.cellText}>{user.username}</span> },
    { id: 'groups', name: t('admin.users.column.groups'), width: '2fr', minWidth: 140, cell: ({ user }) => <span className={styles.cellText}>{groupsOf(user)}</span> },
    { id: 'signsInWith', name: t('admin.users.column.signsInWith'), width: '1fr', minWidth: 120, cell: ({ user }) => <span className={styles.cellText}>{signsInWithWords(user)}</span> },
    {
      id: 'status',
      name: t('admin.users.column.status'),
      width: 110,
      cell: ({ user }) => <StatusDot status={statusDot(user.status)}>{statusWords(user)}</StatusDot>,
    },
    {
      id: 'lastSignIn',
      name: t('admin.users.column.lastSignIn'),
      allowsSorting: true,
      newestFirst: true,
      width: 150,
      cell: ({ user }) => <span className={styles.muted}>{user.lastSignIn === null ? t('admin.users.never') : formatDateTime(user.lastSignIn)}</span>,
    },
    { id: 'devices', name: t('admin.users.column.devices'), align: 'end', width: 84, cell: ({ user }) => formatCount(user.devices) },
  ]
}
