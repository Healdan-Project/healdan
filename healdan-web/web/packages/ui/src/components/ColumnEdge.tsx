// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useContext, useLayoutEffect, useRef, type PointerEvent } from 'react'
import { ColumnResizer, TableColumnResizeStateContext } from 'react-aria-components'
import { t } from '../strings'
import { nameOf } from './headingFocus'
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

/** Puts a heading's edge's slider within reach, while a resize lasts, or out of it */
function reach(slider: HTMLInputElement, within: boolean) {
  slider.disabled = !within
  if (within) slider.removeAttribute('aria-hidden')
  else slider.setAttribute('aria-hidden', 'true')
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
  // The slider is there only while a resize lasts (spec 4.3), which Resize Column in the heading's menu or a press of the edge
  // starts. Outside one it's disabled and hidden from a screen reader: the arrows go from the heading's name to the next
  // heading's, Tab never stops on it, and a screen reader neither reads it in the heading nor changes it, as a change outside
  // a resize would be drawn and kept nowhere (one during a resize is the resize's). React Aria's ColumnResizer takes no props
  // for its slider, so they're set here, before React Aria's own effect focuses it as a resize starts. As a resize ends, the
  // focus goes back to the heading's name, from the slider or the heading itself where React Aria leaves it.
  const wasResizing = useRef(false)
  useLayoutEffect(() => {
    const slider = edge.current?.querySelector('input')
    const heading = edge.current?.closest<HTMLElement>('[role="columnheader"]')
    const active = heading?.ownerDocument.activeElement
    if (wasResizing.current && !isResizing && heading && (active === heading || active === slider)) nameOf(heading)?.focus({ preventScroll: true })
    wasResizing.current = isResizing
    if (slider) reach(slider, isResizing)
  }, [isResizing])
  // A press of the edge's main button starts a resize, and React Aria focuses the slider as the press starts: the slider is
  // within reach from the press, so React Aria's own way with a pointer's resize is as it would be (a touch's tap ends at
  // once only when the slider took the focus as it began). A press that started none leaves it out of reach as it ends.
  function onPointerDownCapture(event: PointerEvent<HTMLDivElement>) {
    const slider = edge.current?.querySelector('input')
    if (!slider || event.button !== 0) return
    reach(slider, true)
    const page = slider.ownerDocument
    const ended = () => {
      page.removeEventListener('pointerup', ended, true)
      page.removeEventListener('pointercancel', ended, true)
      if (latestLayout.current?.resizingColumn !== column) reach(slider, false)
    }
    page.addEventListener('pointerup', ended, true)
    page.addEventListener('pointercancel', ended, true)
  }
  // React Aria names the slider by this and the heading it's in: "Resize Doc #"
  return (
    <ColumnResizer
      ref={edge}
      className={styles.resizer}
      aria-label={t('columns.resizer')}
      data-last={last || undefined}
      onPointerDownCapture={onPointerDownCapture}
      onDoubleClick={() => events.reset(column)}
    />
  )
}
