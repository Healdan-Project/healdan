// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useState } from 'react'
import { useRightRegion } from '../frame/RightRegion'
import { useSelection } from '../selection'

export interface RowSelection {
  /** The one selected document's number, from the address */
  selected: number | undefined
  /** The Table's selection: the keys of the rows */
  selection: Set<string>
  change: (next: Set<string>) => void
}

/**
 * The selection of a list of documents (rows keyed `d<number>`): one document is in the address as ?sel=, which also
 * shows its details; several can't all be in an address, so the view keeps them while it has none.
 */
export function useRowSelection(): RowSelection {
  const region = useRightRegion()
  const { selected, select } = useSelection()
  const [several, setSeveral] = useState<Set<string> | null>(null)
  const selection = several && selected === undefined ? several : new Set(selected === undefined ? [] : [`d${selected}`])

  function change(next: Set<string>) {
    const documents = [...next].filter((key) => key.startsWith('d'))
    setSeveral(documents.length > 1 ? new Set(documents) : null)
    // Clicking a document while Filters are open shows its details, as the owner asked; the funnel brings Filters back.
    if (documents.length === 1 && region.show === 'filters') region.open('details', { reveal: false })
    select(documents.length === 1 ? Number(documents[0]!.slice(1)) : undefined)
  }

  return { selected, selection, change }
}
