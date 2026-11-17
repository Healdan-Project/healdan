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
