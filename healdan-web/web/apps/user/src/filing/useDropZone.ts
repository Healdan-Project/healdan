// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useState, type DragEvent } from 'react'
import type { components } from '@healdan/api'
import { takesDrag, type Place } from './dropTargets'
import { useFiling } from './FilingProvider'

type S = components['schemas']

export interface DropZone {
  /** For the element that takes drops */
  props: {
    onDragOver: (event: DragEvent) => void
    onDragLeave: (event: DragEvent) => void
    onDrop: (event: DragEvent) => void
  }
  /** Something to file is over the zone */
  dropping: boolean
  /** The key of the document row it is over, when one file would be saved into it */
  overRow: string | undefined
}

/** The row of the list a drag event is on, by its key (`d<doc #>`); only a document's row, not a folder's */
function rowKey(event: DragEvent): string | undefined {
  const target = event.target instanceof Element ? event.target : null
  const key = target?.closest('[data-row-key]')?.getAttribute('data-row-key')
  return key?.startsWith('d') ? key : undefined
}

/**
 * A folder's list as a place to drop files (spec 6.5): anywhere on it files into the folder, and on a document's row, one
 * file asks whether it's a new version of it. `documents` finds a row's document by its key.
 */
export function useDropZone(place: Place, documents: (key: string) => S['DocumentView'] | undefined): DropZone {
  const filing = useFiling()
  const [over, setOver] = useState<{ row?: string } | undefined>()

  return {
    dropping: over !== undefined,
    overRow: over?.row,
    props: {
      onDragOver: (event) => {
        if (!takesDrag(Array.from(event.dataTransfer.types))) return
        // Without a cancelled dragover the browser won't drop here
        event.preventDefault()
        // While dragging, only how many items there are is known: one can be a new version
        const items = event.dataTransfer.items
        const row = rowKey(event)
        setOver((now) => (now && now.row === (items && items.length !== 1 ? undefined : row) ? now : { row: items && items.length !== 1 ? undefined : row }))
      },
      onDragLeave: (event) => {
        // Moving onto something inside the zone isn't leaving it
        if (event.relatedTarget instanceof Node && event.currentTarget instanceof Node && event.currentTarget.contains(event.relatedTarget)) return
        setOver(undefined)
      },
      onDrop: (event) => {
        setOver(undefined)
        const key = rowKey(event)
        filing.drop(event, place, key ? documents(key) : undefined)
      },
    },
  }
}
