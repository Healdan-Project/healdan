// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useLayoutEffect, useState } from 'react'

/** --row-height in tokens.css, which the list's height is counted in: the virtualiser needs one of its own */
const ROW = 34

/** The height of a list's box for these rows: a heading, a row at least and `shown` at most, and the box's lines. */
export function rowsHeight(rows: number, shown: number, bar = 0): number {
  return (Math.min(Math.max(rows, 1), shown) + 1) * ROW + 2 + bar
}

/**
 * A list on a page that scrolls as a whole (the Stores and Backends lists, a Store's matters) is as high as its rows, up
 * to `shown` of them, after which it scrolls inside and asks for more. Where the list is too narrow for its columns and
 * the browser's sideways scroll bar takes room of its own, the box is that much higher, so the bar never covers the last
 * row. `boxRef` goes on the box around the `Table`, with `height` as its height.
 */
export function useRowsHeight(rows: number, shown: number) {
  // In state, not a ref: a list drawn only once its answer has come is measured when its box arrives
  const [box, setBox] = useState<HTMLDivElement | null>(null)
  const [bar, setBar] = useState(0)

  // Before the browser paints, so the list isn't drawn once with the bar over its last row
  useLayoutEffect(() => {
    if (box === null) return
    // The list scrolls in its grid (Table's own box): what a scroll bar takes is the grid's height less what it shows
    const measure = () => {
      const grid = box.querySelector<HTMLElement>('[role="grid"]')
      setBar(grid === null ? 0 : Math.max(grid.offsetHeight - grid.clientHeight, 0))
    }
    measure()
    if (typeof ResizeObserver === 'undefined') return
    // The box, and the grid and what it holds: a scroll bar comes or goes with the columns too, the box keeping its size
    const watching = new ResizeObserver(measure)
    watching.observe(box)
    const grid = box.querySelector<HTMLElement>('[role="grid"]')
    if (grid) watching.observe(grid)
    if (grid?.firstElementChild) watching.observe(grid.firstElementChild)
    return () => watching.disconnect()
  }, [box, rows])

  return { boxRef: setBox, height: rowsHeight(rows, shown, bar) }
}
