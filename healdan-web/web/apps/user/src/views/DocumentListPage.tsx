// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { UseQueryResult } from '@tanstack/react-query'
import { useId, useMemo, type ReactNode } from 'react'
import type { components } from '@healdan/api'
import { t, Table, type ItemMenu, type TableColumn, type TableProps, type TableRow } from '@healdan/ui'
import { SelectionActions } from '../actions/SelectionActions'
import { useOpenKey } from '../actions/useOpenKey'
import { DetailsPanel } from '../details/DetailsPanel'
import { QuickLook } from '../preview/QuickLook'
import { ReadingPane } from '../preview/ReadingPane'
import { usePreviewKey } from '../preview/usePreviewKey'
import { ErrorPanel } from '../frame/ErrorPanel'
import { RightRegionView, useRegionKind } from '../frame/RightRegion'
import type { PageOf } from '../pages'
import { useOrganisingTable } from '../organising/useOrganisingTable'
import { Toolbar } from './Toolbar'
import { narrowed, rowOf, type ListRow } from './listColumns'
import { useRowSelection } from './useRowSelection'
import styles from './views.module.css'

type S = components['schemas']

interface ListPageProps<T extends TableRow> {
  heading: string
  empty: string
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

export function ListPage<T extends TableRow>({ heading, empty, columns, rows, query, selection, onOpen, onPreview, region, toolbar, organising, contextMenu }: ListPageProps<T>) {
  const headingId = useId()
  return (
    <section className={styles.withRegion} aria-labelledby={headingId}>
      <div className={`${styles.view} ${styles.folder}`}>
        <h1 id={headingId} className={styles.title}>
          {heading}
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
              empty={query.isPending ? t('common.loading') : empty}
            />
          </>
        )}
      </div>
      {region}
    </section>
  )
}

/** A list of documents the person didn't browse to: Recent Documents, Recent Emails, Checked Out to Me. A document's details show beside it, as in a folder. */
export function DocumentListPage({ heading, empty, actionsLabel, query, columns: allColumns }: { heading: string; empty: string; actionsLabel: string; query: UseQueryResult<PageOf<S['DocumentView']>>; columns: TableColumn<ListRow>[] }) {
  const recent = query
  const items = recent.data?.items
  const { selected, selection, change } = useRowSelection(items)
  const rows = useMemo(() => (items ?? []).map(rowOf), [items])
  // The reading pane makes the list narrow: it keeps the columns that fit
  const columns = useRegionKind({ preview: true, details: selected !== undefined }) === 'preview' ? narrowed(allColumns) : allColumns
  const row = selected === undefined ? undefined : items?.find((doc) => doc.docNumber === selected)
  const preview = usePreviewKey(change)
  const openDocument = useOpenKey(items ?? [], change)
  // Rename, Move To… and Delete, with F2 and Delete, as in every list of documents (R95)
  const organising = useOrganisingTable(items ?? [], selection, change)
  return (
    <ListPage
      heading={heading}
      empty={empty}
      columns={columns}
      rows={rows}
      query={recent}
      selection={{ keys: selection, change }}
      toolbar={
        <Toolbar label={actionsLabel}>
          <SelectionActions document={row} selected={organising.selected} />
        </Toolbar>
      }
      organising={organising.tableProps}
      contextMenu={organising.contextMenu}
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
