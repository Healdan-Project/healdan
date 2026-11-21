// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useNavigate } from '@tanstack/react-router'
import { useCallback, useLayoutEffect, useMemo, useRef } from 'react'
import { ColumnsPanel, t, type TableColumn, type TableColumnChoice, type TableResizing, type TableRow, type TableSort } from '@healdan/ui'
import { useColumnLayout, type DocumentListKind, type ListKind } from './columnLayouts'
import { columnsFor, documentColumns, offeredFor, type ListRow } from './listColumns'
import type { ListingOrder } from './useListing'

/** A list of documents' own kind: `beside` is a layout every list of documents uses beside the reading pane, not a list */
export type OwnDocumentListKind = Exclude<DocumentListKind, 'beside'>

/** What a Table of documents takes for its columns: which show, their widths, and the person's changes to them */
export interface DocumentListColumns {
  columns: TableColumn<ListRow>[]
  resizing: TableResizing
  choice: TableColumnChoice
  onResetColumns: () => void
}

/** What a Table that isn't of documents takes for its columns: they're its own, and only their widths are kept */
export interface ListWidths<T extends TableRow> {
  /** The columns as given: the Table draws each at its kept width, through `resizing` */
  columns: TableColumn<T>[]
  resizing: TableResizing
  onResetColumns: () => void
}

/**
 * A list of documents' columns (spec 4.5, 4.8): the layout of its kind, or beside the reading pane the one layout of every
 * list there, `beside`, which skips a column this list doesn't offer. Resizing, hiding, moving and resetting change only
 * the layout in use. Each prop the Table takes stays the same object while nothing in it changes, so a refetch draws the
 * headings and the Columns panel as they were, the panel open and its focus where it was.
 *
 * @param serverSorted a paged list, which the server sorts: only the columns it sorts by (`SERVER_SORTS`) sort
 */
export function useDocumentListColumns(kind: OwnDocumentListKind, { beside, serverSorted }: { beside: boolean; serverSorted: boolean }): DocumentListColumns {
  const offered = useMemo(() => offeredFor(kind), [kind])
  // Both, at every render, as hooks can't be called by turns
  const own = useColumnLayout(kind, offered)
  const besidePane = useColumnLayout('beside', offered)
  const layout = beside ? besidePane : own
  // The layout's changes are made afresh at every render: the callbacks the Table and the panel take call the last ones, so
  // they stay the same objects
  const latest = useRef(layout)
  useLayoutEffect(() => {
    latest.current = layout
  })

  // The same array while the same columns show: the layout works its list out afresh at every render
  const shownKey = layout.shown.join(' ')
  const shown = useMemo(() => shownKey.split(' '), [shownKey])
  // Matter's heading is the firm's word, which can come after the list first draws (setLabels): the columns are made again then
  const matterWord = t('list.matter')
  // eslint-disable-next-line react-hooks/exhaustive-deps -- matterWord is read inside columnsFor, through Matter's name
  const columns = useMemo(() => columnsFor(kind, shown, serverSorted), [kind, shown, serverSorted, matterWord])
  // The panel names every column the list offers by its heading
  // eslint-disable-next-line react-hooks/exhaustive-deps -- as above
  const names = useMemo(() => columnsFor(kind, offered, false).map(({ id, name }) => ({ id, name })), [kind, offered, matterWord])

  const resized = useCallback((widths: Record<string, number>) => latest.current.resized(widths), [])
  const resetWidth = useCallback((id: string) => latest.current.resetWidth(id), [])
  const show = useCallback((id: string) => latest.current.show(id), [])
  const hide = useCallback((id: string) => latest.current.hide(id), [])
  const move = useCallback((id: string, to: number) => latest.current.move(id, to), [])
  const reset = useCallback(() => latest.current.reset(), [])

  const widths = layout.widths
  const resizing = useMemo<TableResizing>(() => ({ widths, onResizeEnd: resized, onReset: resetWidth }), [widths, resized, resetWidth])
  const forLabel = t('columns.for', { list: t(`columns.kind.${beside ? 'beside' : kind}`) })
  const choice = useMemo<TableColumnChoice>(
    () => ({
      onMove: move,
      onHide: hide,
      panel: <ColumnsPanel forLabel={forLabel} offered={names} shown={shown} onShow={show} onHide={hide} onMove={move} onReset={reset} />,
    }),
    [forLabel, names, shown, show, hide, move, reset],
  )
  return { columns, resizing, choice, onResetColumns: reset }
}

/**
 * A list's columns that aren't of documents (spec 4.8): they keep their order and the list's own, and only the widths of
 * those resized are kept, for the kind. Each prop stays the same object while nothing in it changes, as for documents.
 */
export function useListWidths<T extends TableRow>(kind: ListKind, columns: TableColumn<T>[]): ListWidths<T> {
  const ids = useMemo(() => columns.map((column) => column.id), [columns])
  const layout = useColumnLayout(kind, ids)
  const latest = useRef(layout)
  useLayoutEffect(() => {
    latest.current = layout
  })
  const resized = useCallback((widths: Record<string, number>) => latest.current.resized(widths), [])
  const resetWidth = useCallback((id: string) => latest.current.resetWidth(id), [])
  const reset = useCallback(() => latest.current.reset(), [])
  const widths = layout.widths
  const resizing = useMemo<TableResizing>(() => ({ widths, onResizeEnd: resized, onReset: resetWidth }), [widths, resized, resetWidth])
  return { columns, resizing, onResetColumns: reset }
}

/** A sort as the address holds it: a column's id, and `asc` or `desc` */
export interface AddressSort {
  sort?: string
  order?: ListingOrder
}

/** A route's `validateSearch` for a list the browser sorts: a sort only by a column one of its lists has (`ids`) */
export function sortSearch(search: Record<string, unknown>, ids: readonly string[]): AddressSort {
  return {
    sort: typeof search.sort === 'string' && ids.includes(search.sort) ? search.sort : undefined,
    order: search.order === 'asc' || search.order === 'desc' ? search.order : undefined,
  }
}

/**
 * The sort of a list the server sends whole, which the browser sorts (spec 4.2): the address's, when it names one of the
 * list's columns (`ids`), else the list's own order (`own`), which is a column too, its arrow showing. Choosing the list's
 * own order again leaves the address without one, so a link from the left pane, which has none, opens the list in its own
 * order. Each sort replaces the address, as a search's does, so Back leaves the list.
 */
export function useBrowserSort(ids: readonly string[], own: TableSort, address: AddressSort): { sort: TableSort; onSort: (sort: TableSort) => void } {
  const navigate = useNavigate()
  const asked = address.sort !== undefined && ids.includes(address.sort) ? address.sort : undefined
  const column = asked ?? own.column
  // An address with no order takes the column's first sort, as a click does: a date's newest first
  const first: TableSort['direction'] = asked !== undefined && Object.hasOwn(documentColumns, asked) && documentColumns[asked]!.newestFirst ? 'descending' : 'ascending'
  const direction: TableSort['direction'] = asked === undefined ? own.direction : address.order === 'desc' ? 'descending' : address.order === 'asc' ? 'ascending' : first
  const sort = useMemo(() => ({ column, direction }), [column, direction])
  const { column: ownColumn, direction: ownDirection } = own
  const onSort = useCallback(
    (next: TableSort) => {
      const isOwn = next.column === ownColumn && next.direction === ownDirection
      const kept: AddressSort = isOwn ? { sort: undefined, order: undefined } : { sort: next.column, order: next.direction === 'descending' ? 'desc' : 'asc' }
      void navigate({ to: '.', replace: true, search: (prev) => ({ ...prev, ...kept }) })
    },
    [navigate, ownColumn, ownDirection],
  )
  return { sort, onSort }
}
