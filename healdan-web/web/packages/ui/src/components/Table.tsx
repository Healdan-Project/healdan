// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useState, type KeyboardEvent, type ReactNode } from 'react'
import {
  Cell,
  Collection,
  Column,
  Row,
  Table as AriaTable,
  TableBody,
  TableHeader,
  TableLayout,
  TableLoadMoreItem,
  Virtualizer,
  type Key,
  type Selection,
  type SortDescriptor,
} from 'react-aria-components'
import { Icon } from '../icons'
import { t } from '../strings'
import styles from './Table.module.css'

/** One row: what the Table needs to know of it, besides the caller's own fields. */
export interface TableRow {
  /** Unique among the rows */
  id: string
  /** The plain text a screen reader and type-ahead use */
  textValue: string
  /** False for a row that opens but can't be selected, such as a folder among documents */
  selectable?: boolean
}

export interface TableColumn<T extends TableRow> {
  id: string
  name: string
  /** The column that names each row for a screen reader */
  isRowHeader?: boolean
  allowsSorting?: boolean
  /** Numbers line up at the end */
  align?: 'start' | 'end'
  /** A share of the width left (`'3fr'`) or pixels */
  width?: number | `${number}fr`
  cell: (row: T) => ReactNode
}

export interface TableSort {
  column: string
  direction: 'ascending' | 'descending'
}

export interface TableProps<T extends TableRow> {
  'aria-label'?: string
  'aria-labelledby'?: string
  columns: TableColumn<T>[]
  rows: T[]
  sort?: TableSort
  /** A header was clicked: sort by it, ascending, or the other way if it already sorts */
  onSort?: (sort: TableSort) => void
  selectionMode?: 'single' | 'multiple'
  selection: Set<string>
  onSelectionChange: (keys: Set<string>) => void
  /** Enter or a double-click; a click on a row that can't be selected */
  onOpen?: (key: string) => void
  /** Space */
  onPreview?: (key: string) => void
  /** F2 */
  onRename?: (key: string) => void
  /** Delete, or the Mac's delete key, for the selection, or the focused row when nothing is selected */
  onDelete?: (keys: Set<string>) => void
  /** Called when the end of the rows comes into view and more are to come */
  loadMore?: () => void
  isLoadingMore?: boolean
  /** How many rows there are in all, loaded or not: loadMore is called while fewer are loaded */
  total?: number
  /** What shows when there are no rows */
  empty?: ReactNode
}

function same(keys: Set<Key>, selection: Set<string>): boolean {
  return keys.size === selection.size && [...keys].every((key) => selection.has(String(key)))
}

/** --row-height in tokens.css: the virtualiser lays rows out in pixels */
const ROW_HEIGHT = 34

// React Aria's Table, virtualised so a list of thousands draws only the rows on screen. Healdan's rule for a list's keys
// sits here: Enter opens, Space previews, F2 renames and Delete deletes, each passed out for the view to do.
export function Table<T extends TableRow>({
  columns,
  rows,
  sort,
  onSort,
  selectionMode = 'single',
  selection,
  onSelectionChange,
  onOpen,
  onPreview,
  onRename,
  onDelete,
  loadMore,
  isLoadingMore = false,
  total,
  empty,
  ...labels
}: TableProps<T>) {
  const disabledKeys = rows.filter((row) => row.selectable === false).map((row) => row.id)
  const more = loadMore !== undefined && (total === undefined || rows.length < total)

  // Ahead of React Aria's own keys, which would take Space to select; only for a body row, so a header still sorts.
  function onKeyDownCapture(event: KeyboardEvent<HTMLDivElement>) {
    const row = event.target instanceof Element ? event.target.closest('[data-row-key]') : null
    const key = row?.getAttribute('data-row-key')
    if (!key || event.altKey || event.ctrlKey || event.metaKey) return
    let handled = true
    if (event.key === ' ' && onPreview) onPreview(key)
    else if (event.key === 'F2' && onRename) onRename(key)
    else if ((event.key === 'Delete' || event.key === 'Backspace') && onDelete) onDelete(selection.size > 0 ? new Set(selection) : new Set([key]))
    else handled = false
    if (handled) {
      event.preventDefault()
      event.stopPropagation()
    }
  }

  // React Aria keeps the anchor of a Shift-click range on the selection it hands out, so that one is handed back while
  // the caller's selection still holds the same rows.
  const [given, setGiven] = useState<Selection | null>(null)
  const selectedKeys = given !== null && given !== 'all' && same(given, selection) ? given : selection

  function changeSelection(keys: Selection) {
    setGiven(keys)
    const all = rows.filter((row) => row.selectable !== false).map((row) => row.id)
    onSelectionChange(keys === 'all' ? new Set(all) : new Set([...keys].map(String)))
  }

  const sortDescriptor: SortDescriptor | undefined = sort && { column: sort.column, direction: sort.direction }

  return (
    // The handler only reads keys the rows inside already take; the wrapper isn't a control of its own.
    <div className={styles.frame} onKeyDownCapture={onKeyDownCapture}>
      <Virtualizer layout={TableLayout} layoutOptions={{ rowHeight: ROW_HEIGHT, headingHeight: ROW_HEIGHT, loaderHeight: ROW_HEIGHT }}>
        <AriaTable
          {...labels}
          className={styles.table}
          selectionMode={selectionMode}
          selectionBehavior="replace"
          selectedKeys={selectedKeys}
          onSelectionChange={changeSelection}
          disabledKeys={disabledKeys}
          disabledBehavior="selection"
          sortDescriptor={sortDescriptor}
          onSortChange={(next) => onSort?.({ column: String(next.column), direction: next.direction })}
          onRowAction={onOpen && ((key: Key) => onOpen(String(key)))}
        >
          <TableHeader columns={columns}>
            {(column) => (
              <Column
                id={column.id}
                isRowHeader={column.isRowHeader}
                allowsSorting={column.allowsSorting && onSort !== undefined}
                width={column.width}
                textValue={column.name}
                className={`${styles.column} ${column.align === 'end' ? styles.end : ''}`}
              >
                {({ sortDirection }) => (
                  <span className={styles.heading}>
                    {column.name}
                    {sortDirection && <Icon name={sortDirection === 'ascending' ? 'arrowUp' : 'arrowDown'} size={12} />}
                  </span>
                )}
              </Column>
            )}
          </TableHeader>
          <TableBody renderEmptyState={() => <div className={styles.empty}>{empty}</div>}>
            <Collection items={rows} dependencies={[columns]}>
              {(row) => (
                <Row id={row.id} textValue={row.textValue} columns={columns} dependencies={[columns]} className={styles.row} data-row-key={row.id}>
                  {(column) => <Cell className={`${styles.cell} ${column.align === 'end' ? styles.end : ''}`}>{column.cell(row)}</Cell>}
                </Row>
              )}
            </Collection>
            {more && (
              <TableLoadMoreItem className={styles.loader} onLoadMore={loadMore} isLoading={isLoadingMore}>
                {t('common.loading')}
              </TableLoadMoreItem>
            )}
          </TableBody>
        </AriaTable>
      </Virtualizer>
    </div>
  )
}
