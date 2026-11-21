// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useCallback, useLayoutEffect, useRef, useState } from 'react'
import { forget, keepJson, readJson } from '../preferences'

// A column layout for each kind of list, kept in this browser (spec 4.8): the ids of the columns shown, in order, and the
// widths in pixels of those resized. Both are kept as `healdan.columns.<kind>`, as JSON with a version.

export type DocumentListKind = 'folder' | 'documents' | 'all' | 'emails' | 'recentDocuments' | 'recentEmails' | 'checkedOut' | 'beside'
export type ListKind =
  | DocumentListKind
  | 'folders'
  | 'matters'
  | 'clients'
  | 'recentFolders'
  | 'recentClients'
  | 'trashMine'
  | 'trashEveryone'
  | 'trashRemoved'

export interface ColumnLayout {
  /** The columns shown, in order; absent until the person has chosen columns, so the kind's defaults apply */
  shown?: string[]
  /** The widths in pixels of the columns resized */
  widths: Record<string, number>
}

/** The columns each kind of document list starts with. Mutable only so that a test can stand in for a later release. */
export const DEFAULT_SHOWN: Record<DocumentListKind, string[]> = {
  folder: ['title', 'docNumber', 'version', 'author', 'edited'],
  documents: ['title', 'docNumber', 'version', 'matter', 'author', 'edited'],
  all: ['title', 'docNumber', 'version', 'matter', 'author', 'edited'],
  emails: ['title', 'docNumber', 'matter', 'author', 'edited'],
  recentDocuments: ['title', 'docNumber', 'version', 'matter', 'author', 'edited', 'lastUsed'],
  recentEmails: ['title', 'docNumber', 'matter', 'author', 'edited', 'lastUsed'],
  checkedOut: ['title', 'docNumber', 'version', 'matter', 'computer', 'checkedOut', 'edited'],
  beside: ['title', 'docNumber', 'edited'],
}

const TITLE = 'title'
const VERSION = 1

function isDocumentKind(kind: ListKind): kind is DocumentListKind {
  return kind in DEFAULT_SHOWN
}

const name = (kind: ListKind) => `columns.${kind}`

/** The layout kept for the kind, or one with no columns chosen and no widths when nothing usable is kept */
export function readLayout(kind: ListKind): ColumnLayout {
  const none: ColumnLayout = { widths: {} }
  const value = readJson(name(kind))
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return none
  const { v, shown, widths } = value as Record<string, unknown>
  if (v !== VERSION) return none
  if (shown !== undefined && !(Array.isArray(shown) && shown.every((id) => typeof id === 'string'))) return none
  if (typeof widths !== 'object' || widths === null || Array.isArray(widths)) return none
  const sizes = widths as Record<string, unknown>
  for (const id of Object.keys(sizes)) {
    const size = sizes[id]
    if (typeof size !== 'number' || !Number.isFinite(size) || size <= 0) return none
  }
  return shown === undefined || !isDocumentKind(kind) ? { widths: { ...(sizes as Record<string, number>) } } : { shown: [...(shown as string[])], widths: { ...(sizes as Record<string, number>) } }
}

/** Keeps the layout for the kind, with `shown` only when it has one */
export function keepLayout(kind: ListKind, layout: ColumnLayout): void {
  keepJson(name(kind), layout.shown === undefined ? { v: VERSION, widths: layout.widths } : { v: VERSION, shown: layout.shown, widths: layout.widths })
}

/** Forgets the layout kept for the kind, so its defaults come back */
export function forgetLayout(kind: ListKind): void {
  forget(name(kind))
}

/** The ids to show, in order: Title first; unknown or unoffered ids dropped; the kind's defaults when `shown` is absent */
export function shownIds(kind: ListKind, offered: string[], layout: ColumnLayout): string[] {
  if (!isDocumentKind(kind)) return offered
  const wanted = layout.shown ?? DEFAULT_SHOWN[kind]
  const ids = [TITLE, ...wanted.filter((id) => id !== TITLE)]
  return ids.filter((id, at) => offered.includes(id) && ids.indexOf(id) === at)
}

/** The layout for a kind of list and the changes the table's headings and the Columns panel make to it */
export function useColumnLayout(
  kind: ListKind,
  offered: string[],
): {
  shown: string[]
  widths: Record<string, number>
  resized(widths: Record<string, number>): void
  resetWidth(id: string): void
  show(id: string): void
  hide(id: string): void
  move(id: string, to: number): void
  reset(): void
} {
  const [state, setState] = useState(() => ({ kind, layout: readLayout(kind) }))
  // Calls in one event see each other's changes, which the state alone wouldn't show them until the next render
  const latest = useRef(state)
  const offeredRef = useRef(offered)
  // As each render commits, before any later event: a change made just after the kind changed starts from the new kind's
  useLayoutEffect(() => {
    latest.current = state
    offeredRef.current = offered
  })
  let current = state
  if (state.kind !== kind) {
    // The kind changed: read its layout now, rather than draw the last kind's
    current = { kind, layout: readLayout(kind) }
    setState(current)
  }

  const change = useCallback(
    (make: (layout: ColumnLayout) => ColumnLayout | undefined) => {
      const layout = latest.current.layout
      const next = make(layout)
      if (next === undefined) return
      keepLayout(kind, next)
      latest.current = { kind, layout: next }
      setState(latest.current)
    },
    [kind],
  )
  /** The kept columns, or the kind's defaults when none were chosen; undefined for a kind with none to choose */
  const base = (layout: ColumnLayout) => (isDocumentKind(kind) ? (layout.shown ?? DEFAULT_SHOWN[kind]) : undefined)

  return {
    shown: shownIds(kind, offered, current.layout),
    widths: current.layout.widths,
    resized: (widths) => change((layout) => ({ ...layout, widths: { ...layout.widths, ...widths } })),
    resetWidth: (id) =>
      change((layout) => {
        const rest = { ...layout.widths }
        delete rest[id]
        return { ...layout, widths: rest }
      }),
    show: (id) =>
      change((layout) => {
        const kept = base(layout)
        if (!kept || shownIds(kind, offeredRef.current, layout).includes(id) || kept.includes(id)) return undefined
        return { ...layout, shown: [...kept, id] }
      }),
    hide: (id) =>
      change((layout) => {
        const kept = base(layout)
        if (!kept || id === TITLE || !kept.includes(id)) return undefined
        return { ...layout, shown: kept.filter((one) => one !== id) }
      }),
    move: (id, to) =>
      change((layout) => {
        const kept = base(layout)
        if (!kept || id === TITLE) return undefined
        const list = shownIds(kind, offeredRef.current, layout)
        if (!list.includes(id)) return undefined
        const others = list.filter((one) => one !== id)
        const at = Math.min(Math.max(to, 1), others.length)
        const before = others[at - 1] as string
        const rest = kept.filter((one) => one !== id)
        const place = rest.indexOf(before) + 1
        const moved = [...rest.slice(0, place), id, ...rest.slice(place)]
        // A drag that ends where it began changes nothing, so the kind's defaults still apply
        if (moved.every((one, at) => one === kept[at])) return undefined
        return { ...layout, shown: moved }
      }),
    reset: () => {
      forgetLayout(kind)
      latest.current = { kind, layout: { widths: {} } }
      setState(latest.current)
    },
  }
}
