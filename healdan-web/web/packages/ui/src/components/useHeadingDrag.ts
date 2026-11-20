// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useRef, useState, type DragEvent, type PointerEvent } from 'react'
import styles from './Table.module.css'
import type { TableColumn, TableColumnChoice, TableRow } from './Table'

/** The type a drag of a list's heading carries, with its column's id: never a row's, so no drop target for rows takes it */
export const COLUMN_TYPE = 'application/x-healdan-column'

const headingOf = (event: { target: EventTarget }) => (event.target instanceof Element ? event.target.closest('[data-column-key]') : null)

/**
 * Moving a document list's columns by dragging their headings along the heading row (spec §4.6), with the browser's own drag
 * and drop, as the rows are dragged: React Aria's drag and drop code isn't loaded for it. Only with `choice`. Title, the row
 * header, doesn't drag, and nothing drops before it. A drop past a heading's midpoint puts the column after that heading,
 * before it otherwise; while one would move the column, a line is drawn on the edge where it would go.
 */
export function useHeadingDrag<T extends TableRow>(columns: TableColumn<T>[], choice: TableColumnChoice | undefined) {
  // The column this list's heading drag carries: a drag of another list's heading isn't taken
  const dragging = useRef<string | undefined>(undefined)
  // The latest press began on a heading's edge, whose drag is a resize: the browser fires the drag at the heading
  const pressedEdge = useRef(false)
  // How many elements of the headings the drag is inside: dragenter and dragleave come in pairs for each element it crosses
  const inside = useRef(0)
  // Where the column would go, as the index in `columns` it would be put before; none while it would stay where it is
  const [gap, setGap] = useState<number | undefined>(undefined)

  const movable = (column: TableColumn<T>) => choice !== undefined && column.isRowHeader !== true
  // No column goes before Title
  const first = columns.findIndex((column) => column.isRowHeader) + 1

  /** A drag of this list's heading, over one of its headings */
  const takes = (event: DragEvent) => dragging.current !== undefined && Array.from(event.dataTransfer.types).includes(COLUMN_TYPE) && headingOf(event) !== null

  /** Where the column dragged would go for a drag event over a heading, or none where it is now */
  function gapAt(event: DragEvent): number | undefined {
    const heading = headingOf(event)
    const from = columns.findIndex((column) => column.id === dragging.current)
    const over = columns.findIndex((column) => column.id === heading?.getAttribute('data-column-key'))
    if (!heading || from < 0 || over < 0) return undefined
    const box = heading.getBoundingClientRect()
    const at = Math.max(first, event.clientX > box.left + box.width / 2 ? over + 1 : over)
    return at === from || at === from + 1 ? undefined : at
  }

  function end() {
    dragging.current = undefined
    inside.current = 0
    setGap(undefined)
  }

  /**
   * A dragstart that began in the heading row: a heading's drag carries its column alone, and any other there is cancelled
   * (Title's, and one from an edge). False for one that began elsewhere, which is a row's.
   */
  function start(event: DragEvent): boolean {
    const target = event.target instanceof Element ? event.target : null
    const key = headingOf(event)?.getAttribute('data-column-key')
    if (!choice || !target || key == null) return false
    const column = columns.find((one) => one.id === key)
    if (!column || !movable(column) || pressedEdge.current || target.closest(`.${styles.resizer}`)) {
      event.preventDefault()
      return true
    }
    event.dataTransfer.setData(COLUMN_TYPE, column.id)
    event.dataTransfer.effectAllowed = 'move'
    dragging.current = column.id
    inside.current = 0
    return true
  }

  const frameProps =
    choice === undefined
      ? {}
      : {
          onDragEnter: (event: DragEvent) => {
            if (takes(event)) inside.current++
          },
          onDragOver: (event: DragEvent) => {
            if (!takes(event)) return
            // Without a cancelled dragover the browser won't drop here
            event.preventDefault()
            setGap(gapAt(event))
          },
          onDragLeave: (event: DragEvent) => {
            // Only a drag that entered was counted in, so its leaving is counted out
            if (inside.current === 0 || !takes(event)) return
            inside.current--
            if (inside.current === 0) setGap(undefined)
          },
          onDrop: (event: DragEvent) => {
            const column = dragging.current
            if (column === undefined || !takes(event)) return
            event.preventDefault()
            // A drop zone around the list, as a folder's view has, would take it as a drop with no files
            event.stopPropagation()
            const from = columns.findIndex((one) => one.id === column)
            const at = gapAt(event)
            end()
            if (at !== undefined) choice.onMove(column, at > from ? at - 1 : at)
          },
          onDragEnd: end,
        }

  return {
    /** For the Table's own dragstart: true when it began in the heading row, which a row's drag then leaves alone */
    start,
    /** For the Table's pointerdown, in its capture: where the press began */
    pressed: (event: PointerEvent) => {
      pressedEdge.current = event.target instanceof Element && event.target.closest(`.${styles.resizer}`) !== null
    },
    /** For the element round the Table: the heading row's drop */
    frameProps,
    /** For a heading's ref: draggable while its column can move. React Aria's Column passes on no `draggable`. */
    headingRef: (column: TableColumn<T>) => (element: HTMLElement | null) => {
      if (!element) return
      if (movable(column)) element.draggable = true
      else element.removeAttribute('draggable')
    },
    /** The column after whose heading the drop line is drawn, while one is */
    lineAfter: gap === undefined ? undefined : columns[gap - 1]?.id,
  }
}
