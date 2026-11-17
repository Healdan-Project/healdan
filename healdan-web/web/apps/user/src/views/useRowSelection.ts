// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useMemo, useState } from 'react'
import { useRightRegion } from '../frame/RightRegion'
import { useSelection } from '../selection'

export interface RowSelection {
  /** The one selected document's number, from the address */
  selected: number | undefined
  /** The Table's selection: the keys of the rows */
  selection: Set<string>
  /** Selects the rows, as a click does; `showDetails: false` leaves Filters open where a click would show the details */
  change: (next: Set<string>, options?: { showDetails?: boolean }) => void
}

/**
 * The selection of a list of documents and folders (rows keyed `d<number>` and `f<id>`): one document alone is in the
 * address as ?sel=, which also shows its details; several can't all be in an address, and a folder never is (folders and
 * Trash spec 7.5), so the view keeps them while it has none. A folder has no details: the region's stay a document's (the
 * plan's correction 16). Of those kept, only the ones still listed count: once they are deleted or moved away, nothing is
 * selected, and F2 and Delete take the row the focus is on.
 *
 * @param rows what the list holds, as far as it's loaded
 */
export function useRowSelection(rows?: readonly { type: string; docNumber?: number; id?: number }[]): RowSelection {
  const region = useRightRegion()
  const { selected, select } = useSelection()
  // What the address can't hold: several rows, or any with a folder
  const [inView, setInView] = useState<Set<string> | null>(null)
  const listed = useMemo(
    () => (rows ? new Set(rows.flatMap((row) => (row.type === 'document' ? [`d${row.docNumber}`] : row.type === 'folder' ? [`f${row.id}`] : []))) : undefined),
    [rows],
  )
  const kept = useMemo(() => (inView && listed ? new Set([...inView].filter((key) => listed.has(key))) : inView), [inView, listed])
  const selection = kept && selected === undefined ? kept : new Set(selected === undefined ? [] : [`d${selected}`])

  function change(next: Set<string>, { showDetails = true }: { showDetails?: boolean } = {}) {
    const keys = [...next].filter((key) => key.startsWith('d') || key.startsWith('f'))
    const [only] = keys
    const document = keys.length === 1 && only?.startsWith('d') ? Number(only.slice(1)) : undefined
    setInView(document === undefined && keys.length > 0 ? new Set(keys) : null)
    // Clicking a document while Filters are open shows its details, as the owner asked; the funnel brings Filters back. A
    // right-click, which selects the row for its menu, leaves Filters as they are.
    if (showDetails && document !== undefined && region.show === 'filters') region.open('details', { reveal: false })
    select(document)
  }

  return { selected, selection, change }
}
