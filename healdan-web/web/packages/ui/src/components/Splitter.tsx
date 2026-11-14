// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useEffect, useRef, useState, type KeyboardEvent, type PointerEvent } from 'react'
import { useLocale } from 'react-aria-components'
import { useFocusRing } from 'react-aria/useFocusRing'
import { useMove } from 'react-aria/useMove'
import { t } from '../strings'
import styles from './Splitter.module.css'

/** Set on the page's root element while a divider is dragged: the cursor stays the divider's, and frames don't take the pointer */
export const RESIZING_ATTRIBUTE = 'data-hd-resizing'

/** How far an arrow key moves the divider, and with Shift held */
export const SPLITTER_STEP = 16
export const SPLITTER_BIG_STEP = 64

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
  /** A drag has ended, or a key has moved the divider: the width to keep */
  onResizeEnd: (width: number) => void
  /** Enter or a double-click: back to the pane's default width */
  onReset: () => void
  /** The id of the pane, for `aria-controls` */
  controls?: string
  className?: string
}

const within = (width: number, min: number, max: number) => Math.round(Math.min(max, Math.max(min, width)))

/**
 * A divider between two panes, which a pointer drags (mouse, touch or pen, through React Aria's `useMove`) and the keyboard
 * moves: Left and Right by 16 pixels, 64 with Shift, Home and End to the limits, and Enter (or a double-click) back to the
 * default. It's a focusable `separator` whose value is the pane's width (the window splitter pattern of the ARIA Authoring
 * Practices). A drag reports each width with `onResize`, which a caller can draw without rendering anything again, and the
 * last with `onResizeEnd`; a key reports only `onResizeEnd`. The visible line is a hairline, and the target 24 pixels wide
 * (WCAG 2.5.8), most of it on the side away from the pane.
 */
export function Splitter({ pane, value, minValue, maxValue, onResize, onResizeEnd, onReset, controls, className, ...labels }: SplitterProps) {
  const { direction } = useLocale()
  // The focus ring shows for the keyboard, as React Aria's components show theirs, and not after a drag
  const { focusProps, isFocusVisible } = useFocusRing()
  // Moving to the right widens a pane before the divider in a left-to-right page, and narrows one after it
  const sign = (pane === 'start') === (direction !== 'rtl') ? 1 : -1
  // The width while a drag is under way, which the separator says as its value; the caller draws the pane from onResize
  const [dragged, setDragged] = useState<number>()
  const drag = useRef<{ from: number; moved: number; width: number; told: boolean } | null>(null)
  const latest = useRef(onResizeEnd)
  useEffect(() => {
    latest.current = onResizeEnd
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
      if (!drag.current) return
      const ended = drag.current
      drag.current = null
      document.documentElement.removeAttribute(RESIZING_ATTRIBUTE)
      latest.current(ended.width)
    },
    [],
  )

  const { moveProps } = useMove({
    onMoveStart() {
      drag.current = { from: value, moved: 0, width: value, told: false }
      document.documentElement.setAttribute(RESIZING_ATTRIBUTE, '')
    },
    onMove({ deltaX }) {
      const now = drag.current
      if (!now) return
      now.moved += deltaX
      // From where the drag began, so a pointer taken past a limit and back moves the divider only once it's back there
      const width = within(now.from + sign * now.moved, minValue, maxValue)
      if (now.told && width === now.width) return
      now.width = width
      now.told = true
      setDragged(width)
      onResize?.(width)
    },
    onMoveEnd() {
      const ended = stop()
      if (ended) onResizeEnd(ended.width)
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
    // From the width it's drawn at, which a width that no longer fits is held to
    const current = within(value, minValue, maxValue)
    let width: number | undefined
    if (event.key === 'ArrowRight' || event.key === 'Right') width = current + sign * step
    else if (event.key === 'ArrowLeft' || event.key === 'Left') width = current - sign * step
    else if (event.key === 'Home') width = minValue
    else if (event.key === 'End') width = maxValue
    else if (event.key === 'Enter') {
      event.preventDefault()
      onReset()
      return
    } else return
    event.preventDefault()
    const next = within(width, minValue, maxValue)
    if (next !== current) onResizeEnd(next)
  }

  const now = dragged ?? within(value, minValue, maxValue)
  return (
    <div
      {...moveProps}
      {...focusProps}
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
