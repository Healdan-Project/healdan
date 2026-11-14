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
 * The selection of a list of documents (rows keyed `d<number>`): one document is in the address as ?sel=, which also
 * shows its details; several can't all be in an address, so the view keeps them while it has none. Of those, only the
 * ones still listed count: once they are deleted or moved away, nothing is selected, and F2 and Delete take the row the
 * focus is on.
 *
 * @param rows what the list holds, as far as it's loaded
 */
export function useRowSelection(rows?: readonly { type: string; docNumber?: number }[]): RowSelection {
  const region = useRightRegion()
  const { selected, select } = useSelection()
  const [several, setSeveral] = useState<Set<string> | null>(null)
  const listed = useMemo(() => (rows ? new Set(rows.flatMap((row) => (row.type === 'document' ? [`d${row.docNumber}`] : []))) : undefined), [rows])
  const kept = useMemo(() => (several && listed ? new Set([...several].filter((key) => listed.has(key))) : several), [several, listed])
  const selection = kept && selected === undefined ? kept : new Set(selected === undefined ? [] : [`d${selected}`])

  function change(next: Set<string>, { showDetails = true }: { showDetails?: boolean } = {}) {
    const documents = [...next].filter((key) => key.startsWith('d'))
    setSeveral(documents.length > 1 ? new Set(documents) : null)
    // Clicking a document while Filters are open shows its details, as the owner asked; the funnel brings Filters back. A
    // right-click, which selects the row for its menu, leaves Filters as they are.
    if (showDetails && documents.length === 1 && region.show === 'filters') region.open('details', { reveal: false })
    select(documents.length === 1 ? Number(documents[0]!.slice(1)) : undefined)
  }

  return { selected, selection, change }
}
