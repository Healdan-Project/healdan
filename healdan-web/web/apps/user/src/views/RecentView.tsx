// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useNavigate } from '@tanstack/react-router'
import { useMemo } from 'react'
import type { components } from '@healdan/api'
import { Icon, t, type TableColumn, type TableRow, type TableSort } from '@healdan/ui'
import { clientIcon } from '../kindIcons'
import { useRecentClients, useRecentDocuments, useRecentFolders } from '../queries'
import { useOrganisingTable } from '../organising/useOrganisingTable'
import { isRecentKind } from '../search/scopes'
import { sortRows } from './compareRows'
import { DocumentListPage, ListPage } from './DocumentListPage'
import { NotFound } from './NotFound'
import { columnsFor, offeredFor, rowOf } from './listColumns'
import { useBrowserSort, useListWidths, type AddressSort } from './useListColumns'
import { useRowSelection } from './useRowSelection'
import styles from './views.module.css'

type S = components['schemas']

/** Recent's own order: the newest used first (spec 4.2) */
const LAST_USED: TableSort = { column: 'lastUsed', direction: 'descending' }
/** Recent Clients' own order: by name, A to Z */
const BY_NAME: TableSort = { column: 'name', direction: 'ascending' }

/** Recent Folders' columns, which aren't chosen */
const FOLDER_COLUMNS = ['title', 'matter', 'lastUsed']
/** Recent Clients' columns, which aren't chosen */
const CLIENT_COLUMNS = ['name', 'number']

/** Every column a Recent list sorts by, which the address may name: each list takes only its own (`/recent/$kind`) */
export const RECENT_SORTS: readonly string[] = [...new Set([...offeredFor('recentDocuments'), ...offeredFor('recentEmails'), ...FOLDER_COLUMNS, ...CLIENT_COLUMNS])]

function RecentDocuments({ kind, address }: { kind: 'documents' | 'emails'; address: AddressSort }) {
  const recent = useRecentDocuments(kind === 'emails' ? 'email' : undefined)
  return (
    <DocumentListPage
      heading={t(`recent.heading.${kind}`)}
      empty={t(`recent.empty.${kind}`)}
      actionsLabel={t('recent.actions')}
      query={recent}
      kind={kind === 'emails' ? 'recentEmails' : 'recentDocuments'}
      own={LAST_USED}
      address={address}
    />
  )
}

/**
 * /recent/<kind> (and the start page, which is Recent Documents): what the person worked on lately. The server sends each
 * list whole, newest used first, and the browser sorts it by any column (spec 4.2), the sort in the address. Opening one
 * sets the scope picker to its scope (the top bar follows the address). A document's details show beside the list, as in
 * a folder.
 */
export function RecentView({ kind, sort, order }: { kind: string } & AddressSort) {
  const address = useMemo(() => ({ sort, order }), [sort, order])
  if (!isRecentKind(kind)) return <NotFound />
  if (kind === 'folders') return <RecentFolders address={address} />
  if (kind === 'clients') return <RecentClients address={address} />
  return <RecentDocuments kind={kind} address={address} />
}

function RecentFolders({ address }: { address: AddressSort }) {
  const recent = useRecentFolders()
  const navigate = useNavigate()
  const items = recent.data?.items
  // Made again when the firm's word for a matter, Matter's heading, comes after the list first draws (setLabels)
  const matterWord = t('list.matter')
  // eslint-disable-next-line react-hooks/exhaustive-deps -- matterWord is read inside columnsFor, through Matter's name
  const all = useMemo(() => columnsFor('recentFolders', FOLDER_COLUMNS, false), [matterWord])
  const { columns, ...widths } = useListWidths('recentFolders', all)
  const { sort, onSort } = useBrowserSort(FOLDER_COLUMNS, LAST_USED, address)
  const rows = useMemo(() => sortRows((items ?? []).map(rowOf), sort), [items, sort])
  const folders = useMemo(() => rows.map(({ item }) => item), [rows])
  // Folders select as documents do (folders and Trash spec 7.5): F2 renames the focused one, Delete asks about the selection,
  // and a right-click selects the row first
  const { selection, change } = useRowSelection(items)
  const organising = useOrganisingTable(folders, selection, change)
  return (
    <ListPage
      heading={t('recent.heading.folders')}
      empty={t('recent.empty.folders')}
      columns={columns}
      rows={rows}
      columnProps={{ ...widths, sort, onSort }}
      query={recent}
      selection={{ keys: selection, change }}
      contextMenu={organising.contextMenu}
      organising={organising.tableProps}
      onOpen={(key) => void navigate({ to: '/folders/$id', params: { id: key.slice(1) } })}
    />
  )
}

interface ClientRow extends TableRow {
  client: S['RecentClient']
}

/**
 * Recent Clients in the order asked, by the rules the server sorts by: a name ignoring case, a number as a number, ties in
 * the list's own order either way
 */
function sortClients(rows: ClientRow[], sort: TableSort): ClientRow[] {
  const sign = sort.direction === 'descending' ? -1 : 1
  const keyOf = ({ client }: ClientRow): string | number => (sort.column === 'number' ? client.number : client.name.toLowerCase())
  return [...rows].sort((a, b) => {
    const x = keyOf(a)
    const y = keyOf(b)
    return sign * (x < y ? -1 : x > y ? 1 : 0)
  })
}

function RecentClients({ address }: { address: AddressSort }) {
  const recent = useRecentClients()
  const navigate = useNavigate()
  const all = useMemo<TableColumn<ClientRow>[]>(
    () => [
      {
        id: 'name',
        name: t('list.client'),
        isRowHeader: true,
        allowsSorting: true,
        width: '3fr',
        cell: ({ client }) => (
          <>
            <Icon {...clientIcon} />
            <span className={styles.name}>{client.name}</span>
          </>
        ),
      },
      { id: 'number', name: t('list.clientNumber'), allowsSorting: true, align: 'end', width: 120, cell: ({ client }) => String(client.number) },
    ],
    [],
  )
  const { columns, ...widths } = useListWidths('recentClients', all)
  const { sort, onSort } = useBrowserSort(CLIENT_COLUMNS, BY_NAME, address)
  const rows = useMemo(
    () => sortClients((recent.data ?? []).map((client) => ({ id: `c${client.number}`, textValue: client.name, selectable: false, client })), sort),
    [recent.data, sort],
  )
  // A client has no list of its own (M1): opening one searches its matters
  const open = (key: string) => void navigate({ to: '/search', search: { scope: 'clients', q: key.slice(1) } })
  return <ListPage heading={t('recent.heading.clients')} empty={t('recent.empty.clients')} columns={columns} rows={rows} columnProps={{ ...widths, sort, onSort }} query={recent} onOpen={open} />
}
