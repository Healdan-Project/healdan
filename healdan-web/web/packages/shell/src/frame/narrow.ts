// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useSyncExternalStore } from 'react'

/**
 * Below this the left pane and the right region fold away behind buttons of the top bar (spec 5: designed for 1,280 pixels and
 * wider, and works at a tablet's 768). Between the two they fit, so they stay.
 */
export const NARROW = '(max-width: 1023px)'

function subscribe(onChange: () => void): () => void {
  const query = window.matchMedia?.(NARROW)
  query?.addEventListener('change', onChange)
  return () => query?.removeEventListener('change', onChange)
}

const matches = () => window.matchMedia?.(NARROW).matches ?? false

/** Whether the window is narrow enough that the side panels fold away */
export function useNarrow(): boolean {
  return useSyncExternalStore(subscribe, matches, () => false)
}

// The folded layers that are open, the last opened last. One Esc closes one layer, the innermost: the one opened last.
const layers: string[] = []

/** Records that a layer opened; returns what to call when it closes */
export function openLayer(name: string): () => void {
  layers.push(name)
  return () => {
    const at = layers.lastIndexOf(name)
    if (at >= 0) layers.splice(at, 1)
  }
}

/** Whether the layer was opened last of those open */
export function isInnermost(name: string): boolean {
  return layers[layers.length - 1] === name
}

const FOCUSABLE = 'a[href], button:not(:disabled), input:not([type="hidden"]):not(:disabled), select:not(:disabled), textarea:not(:disabled), [tabindex]:not([tabindex="-1"])'

/** Moves the focus into a layer that has just opened: its first control, or the layer itself when it has none yet */
export function focusInto(element: HTMLElement | null) {
  if (!element) return
  const first = element.querySelector<HTMLElement>(FOCUSABLE)
  if (first) first.focus()
  else {
    element.tabIndex = -1
    element.focus()
  }
}
