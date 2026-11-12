// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useEffect, useRef, useState, type DragEvent, type KeyboardEvent, type ReactNode } from 'react'
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
  /** A row that was just made: it shows one short highlight (none for a person who asks for less motion) */
  flash?: boolean
  /** Something that can be dropped on the row is over it */
  dropping?: boolean
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
  /** The least width in pixels, which the other columns give way for */
  minWidth?: number
  cell: (row: T) => ReactNode
}

/** A cell that is only text goes in a box of its own: a flex cell clips its text with no ellipsis, a box with `text-overflow` doesn't. The whole text stays in it. */
function textual(content: ReactNode): ReactNode {
  return typeof content === 'string' || typeof content === 'number' ? <span className={styles.text}>{content}</span> : content
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
  /**
   * Delete, or the Mac's delete key (Backspace), for the selection, or the focused row when nothing is selected. With
   * ⌘, Ctrl or Option held, the key is left to the browser.
   */
  onDelete?: (keys: Set<string>) => void
  /** Called when the end of the rows comes into view and more are to come */
  loadMore?: () => void
  isLoadingMore?: boolean
  /** How many rows there are in all, loaded or not: loadMore is called while fewer are loaded */
  total?: number
  /** What shows when there are no rows */
  empty?: ReactNode
  /**
   * Rows can be dragged out of the Table, by the browser's own drag and drop. A drag of a row carries `type` with what `data`
   * makes of the keys of the rows being dragged (the selection when the dragged row is in it, else the row), or is cancelled
   * when `data` gives nothing. A row that isn't `selectable` isn't draggable. It is for a pointer: the keyboard has a menu
   * for what a drag does.
   */
  drag?: { type: string; data: (keys: string[]) => string | undefined }
  /**
   * The key of a row that holds a field, which isn't dragged while it does: selecting text in the field would start a drag
   * of the row. It is state the Table draws each time, as the ref callbacks run again on every render and would otherwise
   * make the row draggable again at a refetch.
   */
  undraggable?: string
  /** The rows shown are about to change, as while a new order loads: a screen reader waits for them */
  busy?: boolean
}

function same(keys: Set<Key>, selection: Set<string>): boolean {
  return keys.size === selection.size && [...keys].every((key) => selection.has(String(key)))
}

/** What has the focus when it is on a row: the row itself, or one of its cells */
const rowParts = ['row', 'gridcell', 'rowheader']

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
  busy = false,
  drag,
  undraggable,
  ...labels
}: TableProps<T>) {
  // Rows that can be dragged are draggable, which React Aria's Row has no prop for. A row that is drawn (or drawn again, as a
  // refetch does) takes it from here.
  // `Collection` keeps each row's element with the ref callback of the render that last built it, and the Virtualizer can mount
  // that element again later. So a row's callback reads what is current from a ref, not from the render that made it.
  const latest = useRef({ drag, undraggable })
  useEffect(() => {
    latest.current = { drag, undraggable }
  })
  const canDragNow = (row: T) => latest.current.drag !== undefined && row.selectable !== false && row.id !== latest.current.undraggable
  const draggable = (row: T) => (element: HTMLElement | null) => {
    if (element) element.draggable = canDragNow(row)
  }
  // React Aria draws a row again only when its item or the columns change, so a row that has been drawn already is brought up
  // to date here when `undraggable` or `drag` changes. Drawing every row again for it would make React Aria give the focus back
  // to the row, out of the field that is why the row is undraggable.
  const frame = useRef<HTMLDivElement>(null)
  useEffect(() => {
    const byKey = new Map(rows.map((row) => [row.id, row]))
    for (const element of frame.current?.querySelectorAll<HTMLElement>('[data-row-key]') ?? []) {
      const row = byKey.get(element.getAttribute('data-row-key') ?? '')
      if (row) element.draggable = canDragNow(row)
    }
    // canDrag is made from these
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [rows, drag, undraggable])
  const disabledKeys = rows.filter((row) => row.selectable === false).map((row) => row.id)
  const more = loadMore !== undefined && (total === undefined || rows.length < total)

  // Ahead of React Aria's own keys, which would take Space to select; only when a body row or one of its cells has the
  // focus, so a header still sorts and a control inside a row, such as a field, keeps its keys.
  function onKeyDownCapture(event: KeyboardEvent<HTMLDivElement>) {
    const target = event.target instanceof Element ? event.target : null
    if (!target || !rowParts.includes(target.getAttribute('role') ?? '')) return
    const key = target.closest('[data-row-key]')?.getAttribute('data-row-key')
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

  // A drag that starts on a row carries the selection when the row is in it, and the row alone when it isn't. The browser's
  // own drag and drop, so React Aria's drag and drop code, which is some 140 kB, isn't loaded for it.
  function onDragStart(event: DragEvent<HTMLDivElement>) {
    const target = event.target instanceof Element ? event.target : null
    // Text dragged out of a field in a row is the field's
    if (target?.closest('input, textarea')) return
    const key = target?.closest('[data-row-key]')?.getAttribute('data-row-key')
    const data = drag && key ? drag.data(selection.has(key) ? [...selection] : [key]) : undefined
    if (!drag || data === undefined) return event.preventDefault()
    event.dataTransfer.setData(drag.type, data)
    event.dataTransfer.effectAllowed = 'move'
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
    <div ref={frame} className={styles.frame} onKeyDownCapture={onKeyDownCapture} onDragStart={drag ? onDragStart : undefined} aria-busy={busy || undefined}>
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
                minWidth={column.minWidth}
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
                <Row ref={draggable(row)} id={row.id} textValue={row.textValue} columns={columns} dependencies={[columns]} className={styles.row} data-row-key={row.id} data-flash={row.flash || undefined} data-dropping={row.dropping || undefined}>
                  {(column) => <Cell className={`${styles.cell} ${column.align === 'end' ? styles.end : ''}`}>{textual(column.cell(row))}</Cell>}
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
