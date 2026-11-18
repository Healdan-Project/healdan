// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { components } from '@healdan/api'
import { useQuickLook } from './QuickLookState'

type DocumentView = components['schemas']['DocumentView']

/** The documents among a list's items, in its order: the ones Quick Look walks through. */
export function documentsOf(items: { type: string }[]): DocumentView[] {
  return items.filter((item): item is DocumentView => item.type === 'document')
}

/**
 * What a list does with Space on a row (rows are keyed `d<number>` and `f<id>`): selects the document, as a click does, and
 * opens Quick Look on it. A folder has no preview, so Space only selects it, as it selects a document (folders and Trash
 * spec 7.5); a matter, which isn't selected, it leaves alone.
 */
export function usePreviewKey(select: (keys: Set<string>) => void): (key: string) => void {
  const quickLook = useQuickLook()
  return (key) => {
    if (key.startsWith('f')) return select(new Set([key]))
    if (!key.startsWith('d')) return
    select(new Set([key]))
    quickLook.open(Number(key.slice(1)))
  }
}
