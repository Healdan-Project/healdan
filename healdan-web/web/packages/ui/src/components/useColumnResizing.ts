// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useEffect, useMemo, useRef, useState } from 'react'
import type { Key } from 'react-aria-components'
import type { TableColumn, TableResizing, TableRow } from './Table'

/** What a heading's edge tells the Table: the same object for the Table's life, as React Aria keeps the headings' elements */
export interface EdgeEvents {
  /** React Aria is resizing the column */
  resizing: (column: string) => void
  /** The column's startResize, or none once its edge is gone */
  keepStart: (column: string, start: (() => void) | undefined) => void
  /** The column's edge is gone (the list emptied, or resizing was switched off), during a resize or not */
  gone: (column: string) => void
  /** A double-click on the edge */
  reset: (column: string) => void
}

/** React Aria's widths by column, as its resize handlers hand them */
type Sizes = ReadonlyMap<Key, unknown>

/**
 * A Table's resizing. Every column's width is given (React Aria's controlled width): the width a resize has reached while it
 * lasts, else the kept one, else the column's default. React Aria only reports a resize's widths, which are drawn as it
 * goes and handed to the caller at its end: the pixel widths of the column resized and of the columns to its left, which a
 * resize fixes (React Aria freezes them at their widths then). The columns to its right keep what they had.
 */
export function useColumnResizing<T extends TableRow>(columns: TableColumn<T>[], resizing: TableResizing | undefined) {
  // The widths a resize has reached, while it lasts
  const [resized, setResized] = useState<Record<string, number> | null>(null)
  // The column being resized, as its heading drew it: React Aria's resize handlers don't say
  const resizingColumn = useRef<string | undefined>(undefined)
  // React Aria's widths when the resize began
  const widthsAtStart = useRef<Sizes>(new Map())
  // Each column's startResize, which starts a resize by the keyboard (the heading's menu's Resize Column)
  const starts = useRef(new Map<string, () => void>())
  const latest = useRef(resizing)
  useEffect(() => {
    latest.current = resizing
  })

  const events = useMemo<EdgeEvents>(
    () => ({
      resizing: (column) => {
        resizingColumn.current = column
      },
      keepStart: (column, start) => {
        if (start) starts.current.set(column, start)
        else starts.current.delete(column)
      },
      // A resize cut short: what it reached is no longer drawn, and nothing is handed out
      gone: (column) => {
        if (resizingColumn.current !== column) return
        resizingColumn.current = undefined
        setResized(null)
      },
      reset: (column) => latest.current?.onReset(column),
    }),
    [],
  )

  function widthOf(column: TableColumn<T>): number | `${number}fr` | undefined {
    if (!resizing) return column.width
    return resized?.[column.id] ?? resizing.widths[column.id] ?? column.width ?? '1fr'
  }

  /** React Aria's widths as pixel widths of the column being resized and of the columns to its left */
  function fixedBy(sizes: Sizes): Record<string, number> | undefined {
    const widths: Record<string, number> = {}
    for (const column of columns) {
      const width = sizes.get(column.id)
      if (typeof width === 'number') widths[column.id] = width
      if (column.id === resizingColumn.current) return widths
    }
    return undefined
  }

  function onResizeStart(sizes: Sizes) {
    widthsAtStart.current = sizes
  }

  function onResize(sizes: Sizes) {
    const widths = fixedBy(sizes)
    if (widths) setResized(widths)
  }

  function onResizeEnd(sizes: Sizes) {
    const column = resizingColumn.current
    const widths = fixedBy(sizes)
    resizingColumn.current = undefined
    setResized(null)
    // A press on an edge that moved nothing, as each of a double-click's, fixes nothing
    if (column !== undefined && widths && widths[column] !== widthsAtStart.current.get(column)) resizing?.onResizeEnd(widths)
  }

  /** Starts a resize of the column by the keyboard, as its heading's menu's Resize Column does; none while it has no edge */
  function startResize(column: string) {
    starts.current.get(column)?.()
  }

  return {
    /** The width to give a column's `width` */
    widthOf,
    /** The widths a resize has reached, while one lasts: what the headings are drawn from, with the kept ones */
    resized,
    /** ResizableTableContainer's handlers */
    handlers: { onResizeStart, onResize, onResizeEnd },
    /** What the headings' edges call */
    events,
    startResize,
  }
}
