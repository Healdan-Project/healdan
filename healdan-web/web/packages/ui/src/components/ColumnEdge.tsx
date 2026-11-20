// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useContext, useLayoutEffect, useRef } from 'react'
import { ColumnResizer, TableColumnResizeStateContext } from 'react-aria-components'
import { t } from '../strings'
import styles from './Table.module.css'
import type { EdgeEvents } from './useColumnResizing'

interface ColumnEdgeProps {
  column: string
  /** The id of the heading's text, which alone names the heading */
  headingId: string
  /** The last heading's edge is the list's own, past which nothing is drawn */
  last: boolean
  /** The column's render props: whether React Aria is resizing it, and how to start a resize of it */
  isResizing: boolean
  startResize: () => void
  events: EdgeEvents
}

// A heading's right edge, which a pointer drags to resize its column: React Aria's ColumnResizer, with the hidden slider it
// resizes by from the keyboard, which tells a screen reader the width as it changes. The edge tells the Table which column
// React Aria is resizing, which React Aria's resize handlers don't say, and keeps the column's startResize for the heading's
// menu. A double-click puts the column's default width back.
export function ColumnEdge({ column, headingId, last, isResizing, startResize, events }: ColumnEdgeProps) {
  const edge = useRef<HTMLDivElement>(null)
  const layout = useContext(TableColumnResizeStateContext)
  const latestLayout = useRef(layout)
  useLayoutEffect(() => {
    latestLayout.current = layout
  })
  // Before any later event: the heading draws the resize's start in the render after it, and its moves come after that
  useLayoutEffect(() => {
    if (isResizing) events.resizing(column)
  }, [column, isResizing, events])
  useLayoutEffect(() => {
    events.keepStart(column, startResize)
    return () => events.keepStart(column, undefined)
  }, [column, startResize, events])
  // An edge gone during its resize (the list emptied, or resizing switched off) ends it: React Aria would take the resize up
  // again when the edge came back, with the list's own keys still off, and the Table would still draw what it had reached.
  useLayoutEffect(
    () => () => {
      const state = latestLayout.current
      if (state?.resizingColumn === column) {
        state.endResize()
        state.tableState.setKeyboardNavigationDisabled(false)
      }
      events.gone(column)
    },
    [column, events],
  )
  // The slider inside the heading would add its own name to the heading's, read on every heading, so the heading is named by
  // its text alone. React Aria's Column takes no aria-labelledby, so it's set here.
  useLayoutEffect(() => {
    const heading = edge.current?.closest('[role="columnheader"]')
    heading?.setAttribute('aria-labelledby', headingId)
    return () => heading?.removeAttribute('aria-labelledby')
  }, [headingId])
  // React Aria names the slider by this and the heading it's in: "Resize Doc #"
  return (
    <ColumnResizer
      ref={edge}
      className={styles.resizer}
      aria-label={t('columns.resizer')}
      data-last={last || undefined}
      onDoubleClick={() => events.reset(column)}
    />
  )
}
