// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { adminComponents } from '@healdan/api'
import { formatCount, formatDate, Icon, type TableColumn, type TableRow } from '@healdan/ui'
import { t } from '../strings'
import type { ClientSort } from './useClientList'
import styles from './Clients.module.css'

type A = adminComponents['schemas']

/** A row of the Clients list: the client, keyed by its id */
export interface ClientTableRow extends TableRow {
  client: A['Client']
}

export function clientRowOf(client: A['Client']): ClientTableRow {
  return { id: String(client.id), textValue: t('admin.client.label', { number: client.number, name: client.name }), client }
}

type Column = TableColumn<ClientTableRow> & { id: ClientSort }

/**
 * The Clients list's columns (spec 8.2), each sorting by what the server sorts by under its id: Number, Name, Matters,
 * Documents and Last Activity. A figure the summary hasn't given yet is blank, as is the last activity of a client with
 * nothing filed. Built when the list draws, so the firm's own word for matters (setLabels) is the heading.
 */
export function clientColumns(): TableColumn<ClientTableRow>[] {
  const columns: Column[] = [
    {
      id: 'number',
      name: t('admin.clients.column.number'),
      isRowHeader: true,
      width: 104,
      cell: ({ client }) => (
        <span className={styles.number}>
          <Icon name="building" kind="client" />
          {client.number}
        </span>
      ),
    },
    // The number and the name together name a row, for a screen reader
    { id: 'name', name: t('admin.clients.column.name'), isRowHeader: true, width: '3fr', minWidth: 160, cell: ({ client }) => client.name },
    { id: 'matters', name: t('admin.clients.column.matters'), align: 'end', width: 96, cell: ({ client }) => formatCount(client.matters) },
    { id: 'documents', name: t('admin.clients.column.documents'), align: 'end', width: 104, cell: ({ client }) => (client.documents === null ? '' : formatCount(client.documents)) },
    {
      id: 'lastActivity',
      name: t('admin.clients.column.lastActivity'),
      width: 120,
      cell: ({ client }) => <span className={styles.muted}>{client.lastActivity === null ? '' : formatDate(client.lastActivity)}</span>,
    },
  ]
  return columns.map((column) => ({ ...column, allowsSorting: true }))
}
