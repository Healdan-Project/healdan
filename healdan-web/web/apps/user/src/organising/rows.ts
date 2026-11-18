// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { Movable } from '../actions/batch'
import type { MovingFolder } from '../actions/folders'

/**
 * Names this page's drags: a drag carries it, and a drop moves documents only when it holds this one, so a drag that began in
 * another page (another tab of the app, or any page that happens to use the type) moves nothing. 128 random bits, from
 * getRandomValues, which every page has: randomUUID exists only at a secure address, and this runs as the app loads.
 */
export const pageToken = Array.from(globalThis.crypto.getRandomValues(new Uint8Array(16)), (byte) => byte.toString(16).padStart(2, '0')).join('')

/**
 * What a drag of rows carries under `ROWS_TYPE`: this page's token, the documents' numbers, names and folders, and the
 * folders dragged with them (folders and Trash spec 7.5), with what a move or a delete needs of them: where each is, its
 * matter, and what it allows, with its kind and level, which say why it can't be moved.
 */
export function rowsPayload(documents: readonly Movable[], folders: readonly MovingFolder[] = []): string {
  return JSON.stringify({
    token: pageToken,
    documents: documents.map(({ docNumber, name, folder }) => ({ docNumber, name, folder: folder ? { id: folder.id } : undefined })),
    folders: folders.map(({ id, name, parentId, matterId, canMove, canDelete, kind, topLevel }) => ({ id, name, parentId, matterId, canMove, canDelete, kind, topLevel })),
  })
}

const KINDS: readonly unknown[] = ['store', 'matter', 'folder', 'searchFolder'] satisfies MovingFolder['kind'][]

const isId = (value: unknown): value is number => typeof value === 'number' && Number.isSafeInteger(value)

function documentOf(one: unknown): Movable[] {
  const { docNumber, name, folder } = (one ?? {}) as { docNumber?: unknown; name?: unknown; folder?: { id?: unknown } }
  if (!isId(docNumber) || typeof name !== 'string') return []
  return [typeof folder?.id === 'number' ? { docNumber, name, folder: { id: folder.id } } : { docNumber, name }]
}

function folderOf(one: unknown): MovingFolder[] {
  const { id, name, parentId, matterId, canMove, canDelete, kind, topLevel } = (one ?? {}) as Record<string, unknown>
  if (!isId(id) || typeof name !== 'string' || !isId(parentId) || !(matterId === null || isId(matterId))) return []
  if (typeof canMove !== 'boolean' || typeof canDelete !== 'boolean') return []
  if (!(kind === undefined || KINDS.includes(kind)) || !(topLevel === undefined || typeof topLevel === 'boolean')) return []
  const folder: MovingFolder = { id, name, parentId, matterId, canMove, canDelete }
  if (kind !== undefined) folder.kind = kind as MovingFolder['kind']
  if (topLevel !== undefined) folder.topLevel = topLevel
  return [folder]
}

/**
 * The documents and folders a drag of this page carried; nothing for data that isn't what `rowsPayload` makes, or has
 * another token, and nothing of an entry that isn't whole.
 */
export function readRows(text: string): { documents: Movable[]; folders: MovingFolder[] } {
  try {
    const parsed = JSON.parse(text) as { token?: unknown; documents?: unknown; folders?: unknown } | null
    if (parsed?.token !== pageToken) return { documents: [], folders: [] }
    return {
      documents: Array.isArray(parsed.documents) ? parsed.documents.flatMap(documentOf) : [],
      folders: Array.isArray(parsed.folders) ? parsed.folders.flatMap(folderOf) : [],
    }
  } catch {
    return { documents: [], folders: [] }
  }
}

/** Whether a list draws the row with this key now (`d10388` for a document) */
export function rowDrawn(key: string): boolean {
  return globalThis.document.querySelector(`[role="grid"] [data-row-key="${key}"]`) !== null
}

/**
 * Where the focus goes once the rows with these keys (`d10388` for a document, `f45` for a folder) are gone: the key of the
 * row that follows them in the list drawn now, else the one before them, else none. Undefined when none of them is a row
 * of the list (a folder of the tree, or the open one).
 */
export function rowAfterKeys(gone: readonly string[]): { key?: string } | undefined {
  const leaving = new Set(gone)
  const keys = Array.from(globalThis.document.querySelectorAll('[role="grid"] [data-row-key]'), (row) => row.getAttribute('data-row-key') ?? '')
  const last = keys.findLastIndex((key) => leaving.has(key))
  if (last < 0) return undefined
  return { key: keys.slice(last + 1).find((key) => !leaving.has(key)) ?? keys.slice(0, last).findLast((key) => !leaving.has(key)) }
}

/** The key of the row that follows the documents' rows in the list drawn now, else the one before them, else none */
export function rowAfter(docNumbers: readonly number[]): string | undefined {
  return rowAfterKeys(docNumbers.map((n) => `d${n}`))?.key
}

/**
 * Focuses a row once it has been drawn again, or the list when there is no such row (as the dialog that had the focus
 * gives it back first). With `gone`, the row keys of documents that may have left the list, only once none of them is
 * drawn: where they stay, as a released document does in a folder, the focus stays where it is.
 */
export function focusLater(key: string | undefined, gone: readonly string[] = []) {
  let tries = 0
  const attempt = () => {
    // A browser runs no timer of a page that has gone, but a test's page (jsdom's) goes with the timer still set, and
    // where the rows stay drawn the tries last a second
    if (!globalThis.document) return
    const target = gone.some(rowDrawn)
      ? null
      : ((key ? globalThis.document.querySelector<HTMLElement>(`[data-row-key="${key}"]`) : null) ?? (key ? null : globalThis.document.querySelector<HTMLElement>('[role="grid"]')))
    if (target) target.focus()
    else if (++tries < 20) setTimeout(attempt, 50)
  }
  setTimeout(attempt, 0)
}
