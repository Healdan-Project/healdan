// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useNavigate } from '@tanstack/react-router'
import { useMemo } from 'react'
import type { components } from '@healdan/api'
import { Icon, t, type TableColumn, type TableRow } from '@healdan/ui'
import { clientIcon } from '../kindIcons'
import { useRecentClients, useRecentDocuments, useRecentFolders } from '../queries'
import { useOrganisingTable } from '../organising/useOrganisingTable'
import { isRecentKind } from '../search/scopes'
import { DocumentListPage, ListPage } from './DocumentListPage'
import { NotFound } from './NotFound'
import { hitColumns, rowOf } from './listColumns'
import { useRowSelection } from './useRowSelection'
import styles from './views.module.css'

type S = components['schemas']

function RecentDocuments({ kind }: { kind: 'documents' | 'emails' }) {
  const recent = useRecentDocuments(kind === 'emails' ? 'email' : undefined)
  const columns = useMemo(() => hitColumns('documents'), [])
  return <DocumentListPage heading={t(`recent.heading.${kind}`)} empty={t(`recent.empty.${kind}`)} actionsLabel={t('recent.actions')} query={recent} columns={columns} />
}

/**
 * /recent/<kind> (and the start page, which is Recent Documents): what the person worked on lately, in the order the
 * server gives (newest first), so no header sorts. Opening one sets the scope picker to its scope (the top bar follows
 * the address). A document's details show beside the list, as in a folder.
 */
export function RecentView({ kind }: { kind: string }) {
  if (!isRecentKind(kind)) return <NotFound />
  if (kind === 'folders') return <RecentFolders />
  if (kind === 'clients') return <RecentClients />
  return <RecentDocuments kind={kind} />
}

function RecentFolders() {
  const recent = useRecentFolders()
  const navigate = useNavigate()
  const items = recent.data?.items
  const rows = useMemo(() => (items ?? []).map(rowOf), [items])
  const columns = useMemo(() => hitColumns('folders'), [])
  // Folders select as documents do (folders and Trash spec 7.5): F2 renames the focused one, Delete asks about the selection,
  // and a right-click selects the row first
  const { selection, change } = useRowSelection(items)
  const organising = useOrganisingTable(items ?? [], selection, change)
  return (
    <ListPage
      heading={t('recent.heading.folders')}
      empty={t('recent.empty.folders')}
      columns={columns}
      rows={rows}
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

function RecentClients() {
  const recent = useRecentClients()
  const navigate = useNavigate()
  const rows = useMemo<ClientRow[]>(() => (recent.data ?? []).map((client) => ({ id: `c${client.number}`, textValue: client.name, selectable: false, client })), [recent.data])
  const columns = useMemo<TableColumn<ClientRow>[]>(
    () => [
      {
        id: 'name',
        name: t('list.client'),
        isRowHeader: true,
        width: '3fr',
        cell: ({ client }) => (
          <>
            <Icon {...clientIcon} />
            <span className={styles.name}>{client.name}</span>
          </>
        ),
      },
      { id: 'number', name: t('list.clientNumber'), align: 'end', width: 120, cell: ({ client }) => String(client.number) },
    ],
    [],
  )
  // A client has no list of its own (M1): opening one searches its matters
  const open = (key: string) => void navigate({ to: '/search', search: { scope: 'clients', q: key.slice(1) } })
  return <ListPage heading={t('recent.heading.clients')} empty={t('recent.empty.clients')} columns={columns} rows={rows} query={recent} onOpen={open} />
}
