// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useRef, useState, type DragEvent } from 'react'
import type { components } from '@healdan/api'
import { takesDrag, type Place } from './dropTargets'
import { useFiling } from './FilingProvider'

type S = components['schemas']

/** What a list's row stands for as a drop target: a document, which a file may become the next version of, or a folder */
export type RowTarget = { document: S['DocumentView'] } | { place: Place }

export interface DropZone {
  /** For the element that takes drops */
  props: {
    onDragEnter: (event: DragEvent) => void
    onDragOver: (event: DragEvent) => void
    onDragLeave: (event: DragEvent) => void
    onDrop: (event: DragEvent) => void
  }
  /** Something to file is over the list, and not over a row of it */
  dropping: boolean
  /** The key of the row it is over: a folder's, or a document's when one file could be saved into it */
  overRow: string | undefined
}

/** The row of the list a drag event is on, by its key (`d<doc #>` or `f<id>`) */
function rowKey(event: DragEvent): string | undefined {
  const target = event.target instanceof Element ? event.target : null
  const key = target?.closest('[data-row-key]')?.getAttribute('data-row-key')
  return key?.startsWith('d') || key?.startsWith('f') ? key : undefined
}

/**
 * A folder's list as a place to drop files (spec 6.5, R69, R70): anywhere on it files into the folder; on a folder's row it
 * files into that folder, by the same rules as a folder in the tree; and on a document's row one file asks whether it's a
 * new version of it. `rowAt` says what a row stands for. The row under the drag lights up, not the whole list.
 */
export function useDropZone(place: Place, rowAt: (key: string) => RowTarget | undefined): DropZone {
  const filing = useFiling()
  const [over, setOver] = useState<{ row?: string } | undefined>()
  // How many elements of the zone the drag is inside: dragenter and dragleave come in pairs for each element it crosses,
  // and WebKit may name none as dragleave's relatedTarget, so a leave is counted, never traced
  const inside = useRef(0)

  return {
    dropping: over !== undefined && over.row === undefined,
    overRow: over?.row,
    props: {
      onDragEnter: (event) => {
        if (takesDrag(Array.from(event.dataTransfer.types))) inside.current++
      },
      onDragOver: (event) => {
        if (!takesDrag(Array.from(event.dataTransfer.types))) return
        // Without a cancelled dragover the browser won't drop here
        event.preventDefault()
        const key = rowKey(event)
        const target = key ? rowAt(key) : undefined
        // While dragging, only how many items there are is known: one can be a new version
        const items = event.dataTransfer.items
        const lit = target && ('place' in target || !items || items.length === 1) ? key : undefined
        setOver((now) => (now && now.row === lit ? now : { row: lit }))
      },
      onDragLeave: () => {
        if (inside.current === 0) return
        inside.current--
        if (inside.current === 0) setOver(undefined)
      },
      onDrop: (event) => {
        inside.current = 0
        setOver(undefined)
        const key = rowKey(event)
        const target = key ? rowAt(key) : undefined
        if (target && 'place' in target) filing.drop(event, target.place)
        else filing.drop(event, place, target?.document)
      },
    },
  }
}
