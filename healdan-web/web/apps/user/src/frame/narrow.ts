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
