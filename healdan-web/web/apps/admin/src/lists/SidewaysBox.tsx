// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useLayoutEffect, useRef, type ReactNode } from 'react'

/**
 * While the box is narrower than what it holds, so it scrolls sideways, a Tab stop of its own, which the arrow keys then
 * scroll (WCAG 2.1.1), and a region named `name`, so a screen reader says what the stop is; none otherwise. Set on the
 * element, which React draws with no tabindex, role or name, as it's what the browser has laid out.
 */
function markStop(box: HTMLElement, name: string) {
  if (box.scrollWidth > box.clientWidth) {
    box.tabIndex = 0
    box.setAttribute('role', 'region')
    box.setAttribute('aria-label', name)
  } else {
    box.removeAttribute('tabindex')
    box.removeAttribute('role')
    box.removeAttribute('aria-label')
  }
}

/**
 * A box a wide table scrolls sideways in, as the Search Index's tables at 768 pixels: a named region and a Tab stop only
 * while it scrolls (markStop), looked at after each render, as rows come and go, and whenever its size or the table's
 * changes, as its width follows the window's. `name` must be unique among the page's regions.
 */
export function SidewaysBox({ name, className, children }: { name: string; className?: string; children: ReactNode }) {
  const box = useRef<HTMLDivElement>(null)
  useLayoutEffect(() => {
    if (box.current) markStop(box.current, name)
  })
  useLayoutEffect(() => {
    const element = box.current
    if (element === null || typeof ResizeObserver === 'undefined') return
    const observer = new ResizeObserver(() => markStop(element, name))
    observer.observe(element)
    // And what it holds: a box at the window's width keeps its size as the table in it widens
    if (element.firstElementChild) observer.observe(element.firstElementChild)
    return () => observer.disconnect()
  }, [name])
  return (
    <div ref={box} className={className}>
      {children}
    </div>
  )
}
