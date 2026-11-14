// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useEffect, useLayoutEffect, useRef, useState, type KeyboardEvent, type PointerEvent } from 'react'
import { useFocusRing } from 'react-aria/useFocusRing'
import { useMove } from 'react-aria/useMove'
import { t } from '../strings'
import styles from './Splitter.module.css'

/** Set on the page's root element while a divider is dragged: the cursor stays the divider's, and frames don't take the pointer */
export const RESIZING_ATTRIBUTE = 'data-hd-resizing'

/** How far an arrow key moves the divider, and with Shift held */
const SPLITTER_STEP = 16
const SPLITTER_BIG_STEP = 64

export interface SplitterProps {
  /** What it resizes, as "Resize Navigation" */
  'aria-label': string
  /**
   * Which side of the divider the pane it resizes is on: `start` for one before it (the left pane, in English), `end` for one
   * after it. The divider sits at that pane's edge, in a box whose inline start or end is the edge (see the CSS).
   */
  pane: 'start' | 'end'
  /** The pane's width now, in pixels */
  value: number
  minValue: number
  maxValue: number
  /** While a drag moves the divider: the width it has reached, within the limits. The first call starts the drag. */
  onResize?: (width: number) => void
  /** A drag has ended, or a key has moved the divider, at a width other than the one the pane is drawn at: the width to keep */
  onResizeEnd: (width: number) => void
  /** Enter or a double-click: back to the pane's default width */
  onReset: () => void
  /** The id of the pane, for `aria-controls` */
  controls?: string
  className?: string
}

/** A width within the limits, in whole pixels: the one clamp a divider and the layout it draws share */
export function clampWidth(width: number, min: number, max: number): number {
  return Math.round(Math.min(max, Math.max(min, width)))
}

/**
 * How moving to the right changes the pane's width: it widens a pane before the divider and narrows one after it, the other
 * way round in a page drawn right to left. It's the direction the divider is drawn in, not the browser's language, which a
 * page in English is drawn left to right whatever it asks for.
 */
function signOf(element: Element | null, pane: 'start' | 'end'): number {
  const rtl = element !== null && getComputedStyle(element).direction === 'rtl'
  return (pane === 'start') === !rtl ? 1 : -1
}

/**
 * On iOS, useMove's start keeps the whole page from selecting text until its end, which a drag cut short never reaches: give
 * it back, as its end would
 */
function restoreTextSelection() {
  const root = document.documentElement.style
  if (root.webkitUserSelect === 'none') root.webkitUserSelect = ''
}

interface Drag {
  /** The width the pane was drawn at when the drag began */
  from: number
  sign: number
  moved: number
  /** The width it has reached */
  width: number
  /** Whether a width other than `from` has been reported, which the caller draws */
  reported: boolean
}

/**
 * A divider between two panes, which a pointer drags (mouse, touch or pen, through React Aria's `useMove`) and the keyboard
 * moves: Left and Right by 16 pixels, 64 with Shift, Home and End to the limits, and Enter (or a double-click) back to the
 * default. It's a focusable `separator` whose value is the pane's width (the window splitter pattern of the ARIA Authoring
 * Practices). A drag reports each width with `onResize`, which a caller can draw without rendering anything again, and the
 * last with `onResizeEnd`; a key reports only `onResizeEnd`. Neither reports a width the pane is already drawn at. The visible
 * line is a hairline, and the target 24 pixels wide (WCAG 2.5.8), most of it on the side away from the pane.
 */
export function Splitter({ pane, value, minValue, maxValue, onResize, onResizeEnd, onReset, controls, className, ...labels }: SplitterProps) {
  // The focus ring shows for the keyboard, as React Aria's components show theirs, and not after a drag
  const { focusProps, isFocusVisible } = useFocusRing()
  const element = useRef<HTMLDivElement>(null)
  // The width it's drawn at, which a width that no longer fits is held to
  const drawn = clampWidth(value, minValue, maxValue)
  // The width while a drag is under way, which the separator says as its value; the caller draws the pane from onResize
  const [dragged, setDragged] = useState<number>()
  const drag = useRef<Drag | null>(null)
  const latest = useRef({ onResizeEnd, drawn })
  useEffect(() => {
    latest.current = { onResizeEnd, drawn }
  })

  function stop() {
    document.documentElement.removeAttribute(RESIZING_ATTRIBUTE)
    const ended = drag.current
    drag.current = null
    setDragged(undefined)
    return ended
  }

  // A drag the divider doesn't see the end of, as when the window narrows and the divider goes, still keeps its width
  useEffect(
    () => () => {
      const ended = drag.current
      if (!ended) return
      drag.current = null
      document.documentElement.removeAttribute(RESIZING_ATTRIBUTE)
      restoreTextSelection()
      if (ended.width !== latest.current.drawn) latest.current.onResizeEnd(ended.width)
    },
    [],
  )

  // From where the drag began, so a pointer taken past a limit and back moves the divider only once it's back there
  const reached = (now: Drag) => clampWidth(now.from + now.sign * now.moved, minValue, maxValue)

  // The caller drawn again while a drag is under way (the window resized, so it has new limits and a new onResize) draws its
  // widths from what it kept, not from the drag: the drag's width is reported again, before the page is painted
  useLayoutEffect(() => {
    const now = drag.current
    if (!now?.reported) return
    now.width = reached(now)
    onResize?.(now.width)
    // eslint-disable-next-line react-hooks/exhaustive-deps -- only a new caller or new limits call for it again
  }, [minValue, maxValue, onResize])

  const { moveProps } = useMove({
    onMoveStart() {
      drag.current = { from: drawn, sign: signOf(element.current, pane), moved: 0, width: drawn, reported: false }
      document.documentElement.setAttribute(RESIZING_ATTRIBUTE, '')
    },
    onMove({ deltaX }) {
      const now = drag.current
      if (!now) return
      now.moved += deltaX
      // Only a width that changes is reported: a pointer that jitters, or moves only up and down, resizes nothing
      const width = reached(now)
      if (width === now.width) return
      now.width = width
      now.reported = true
      setDragged(width)
      onResize?.(width)
    },
    onMoveEnd() {
      const ended = stop()
      // A drag that ends at the width the pane is drawn at keeps nothing, so a press that only jittered doesn't keep a width
      // held to a limit, or fix one that follows the window
      if (ended && ended.width !== drawn) onResizeEnd(ended.width)
    },
  })

  function onPointerDown(event: PointerEvent<HTMLDivElement>) {
    moveProps.onPointerDown?.(event)
    // Every move then comes here, even over a frame such as an email's body, which would otherwise keep the pointer's moves
    try {
      event.currentTarget.setPointerCapture?.(event.pointerId)
    } catch {
      // A pointer the browser no longer tracks: the window's listeners still follow it
    }
  }

  // The keys, in place of useMove's own (which move a pixel at a time and know no Home, End or Enter)
  function onKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    if (event.altKey || event.ctrlKey || event.metaKey) return
    const step = event.shiftKey ? SPLITTER_BIG_STEP : SPLITTER_STEP
    const sign = signOf(event.currentTarget, pane)
    let width: number | undefined
    if (event.key === 'ArrowRight' || event.key === 'Right') width = drawn + sign * step
    else if (event.key === 'ArrowLeft' || event.key === 'Left') width = drawn - sign * step
    else if (event.key === 'Home') width = minValue
    else if (event.key === 'End') width = maxValue
    else if (event.key === 'Enter') {
      event.preventDefault()
      onReset()
      return
    } else return
    event.preventDefault()
    const next = clampWidth(width, minValue, maxValue)
    if (next !== drawn) onResizeEnd(next)
  }

  const now = dragged === undefined ? drawn : clampWidth(dragged, minValue, maxValue)
  return (
    <div
      {...moveProps}
      {...focusProps}
      ref={element}
      onPointerDown={onPointerDown}
      onKeyDown={onKeyDown}
      onDoubleClick={() => onReset()}
      role="separator"
      tabIndex={0}
      aria-orientation="vertical"
      aria-label={labels['aria-label']}
      aria-controls={controls}
      aria-valuenow={now}
      aria-valuemin={minValue}
      aria-valuemax={maxValue}
      aria-valuetext={t('splitter.value', { n: now })}
      data-pane={pane}
      data-dragging={dragged !== undefined || undefined}
      data-focus-visible={isFocusVisible || undefined}
      className={`${styles.splitter} ${className ?? ''}`}
    />
  )
}
