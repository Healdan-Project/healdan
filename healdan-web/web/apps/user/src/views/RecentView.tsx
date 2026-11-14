// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useNavigate } from '@tanstack/react-router'
import { useId, useMemo, type ReactNode } from 'react'
import type { components } from '@healdan/api'
import { Icon, t, Table, type ItemMenu, type TableColumn, type TableProps, type TableRow } from '@healdan/ui'
import { SelectionActions } from '../actions/SelectionActions'
import { useOpenKey } from '../actions/useOpenKey'
import { DetailsPanel } from '../details/DetailsPanel'
import { QuickLook } from '../preview/QuickLook'
import { ReadingPane } from '../preview/ReadingPane'
import { usePreviewKey } from '../preview/usePreviewKey'
import { ErrorPanel } from '../frame/ErrorPanel'
import { RightRegionView, useRegionKind } from '../frame/RightRegion'
import { useRecentClients, useRecentDocuments, useRecentFolders } from '../queries'
import { useOrganisingTable } from '../organising/useOrganisingTable'
import { useListMenu } from '../menus/useListMenu'
import { isRecentKind, type RecentKind } from '../search/scopes'
import { NotFound } from './NotFound'
import { Toolbar } from './Toolbar'
import { hitColumns, narrowed, rowOf } from './listColumns'
import { useRowSelection } from './useRowSelection'
import styles from './views.module.css'

type S = components['schemas']

const NONE: ReadonlySet<string> = new Set()

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

interface ListProps<T extends TableRow> {
  kind: RecentKind
  columns: TableColumn<T>[]
  rows: T[]
  query: { isError: boolean; isPending: boolean; error: unknown; refetch: () => unknown }
  selection?: { keys: Set<string>; change: (next: Set<string>) => void }
  onOpen?: (key: string) => void
  /** Space */
  onPreview?: (key: string) => void
  /** The right region, when the list has one */
  region?: ReactNode
  /** The toolbar above a list of documents */
  toolbar?: ReactNode
  /** F2, Delete and dragging, for a list of documents */
  organising?: Pick<TableProps<T>, 'onRename' | 'onDelete' | 'drag' | 'undraggable'>
  /** The rows' right-click menu */
  contextMenu?: ItemMenu
}

function RecentList<T extends TableRow>({ kind, columns, rows, query, selection, onOpen, onPreview, region, toolbar, organising, contextMenu }: ListProps<T>) {
  const headingId = useId()
  return (
    <section className={styles.withRegion} aria-labelledby={headingId}>
      <div className={`${styles.view} ${styles.folder}`}>
        <h1 id={headingId} className={styles.title}>
          {t(`recent.heading.${kind}`)}
        </h1>
        {query.isError ? (
          <ErrorPanel error={query.error} onRetry={() => void query.refetch()} />
        ) : (
          <>
            {toolbar}
            <Table
              aria-labelledby={headingId}
              columns={columns}
              rows={rows}
              selectionMode={selection ? 'multiple' : 'single'}
              selection={selection?.keys ?? new Set()}
              onSelectionChange={selection?.change ?? (() => {})}
              onOpen={onOpen}
              onPreview={onPreview}
              {...organising}
              contextMenu={contextMenu}
              empty={query.isPending ? t('common.loading') : t(`recent.empty.${kind}`)}
            />
          </>
        )}
      </div>
      {region}
    </section>
  )
}

function RecentDocuments({ kind }: { kind: 'documents' | 'emails' }) {
  const recent = useRecentDocuments(kind === 'emails' ? 'email' : undefined)
  const items = recent.data?.items
  const { selected, selection, change } = useRowSelection(items)
  const rows = useMemo(() => (items ?? []).map(rowOf), [items])
  const allColumns = useMemo(() => hitColumns('documents'), [])
  // The reading pane makes the list narrow: it keeps the columns that fit
  const columns = useRegionKind({ preview: true, details: selected !== undefined }) === 'preview' ? narrowed(allColumns) : allColumns
  const row = selected === undefined ? undefined : items?.find((doc) => doc.docNumber === selected)
  const preview = usePreviewKey(change)
  const openDocument = useOpenKey(items ?? [], change)
  // Rename, Move To… and Delete, with F2 and Delete, as in every list of documents (R95)
  const organising = useOrganisingTable(items ?? [], selection)
  const menu = useListMenu({ rows: items ?? [], selection, onSelect: change, selected: organising.selected })
  return (
    <RecentList
      kind={kind}
      columns={columns}
      rows={rows}
      query={recent}
      selection={{ keys: selection, change }}
      toolbar={
        <Toolbar label={t('recent.actions')}>
          <SelectionActions document={row} selected={organising.selected} />
        </Toolbar>
      }
      organising={organising.tableProps}
      contextMenu={menu}
      // A document opens in its app, or downloads if the helper doesn't open its kind, and is selected
      onOpen={openDocument}
      onPreview={preview}
      region={
        <>
          <RightRegionView
            details={selected !== undefined && <DetailsPanel key={selected} docNumber={selected} row={row} context="search" />}
            preview={<ReadingPane docNumber={selected} row={row} />}
          />
          <QuickLook documents={items ?? []} />
        </>
      }
    />
  )
}

function RecentFolders() {
  const recent = useRecentFolders()
  const navigate = useNavigate()
  const items = recent.data?.items
  const rows = useMemo(() => (items ?? []).map(rowOf), [items])
  const columns = useMemo(() => hitColumns('folders'), [])
  // A folder's row is never selected, so its menu leaves the (empty) selection as it is
  const menu = useListMenu({ rows: items ?? [], selection: NONE, onSelect: () => {}, selected: [] })
  return <RecentList kind="folders" columns={columns} rows={rows} query={recent} contextMenu={menu} onOpen={(key) => void navigate({ to: '/folders/$id', params: { id: key.slice(1) } })} />
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
            <Icon name="building" />
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
  return <RecentList kind="clients" columns={columns} rows={rows} query={recent} onOpen={open} />
}
