// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { Movable } from '../actions/batch'

/**
 * Names this page's drags: a drag carries it, and a drop moves documents only when it holds this one, so a drag that began in
 * another page (another tab of the app, or any page that happens to use the type) moves nothing.
 */
export const pageToken = globalThis.crypto.randomUUID()

/** What a drag of rows carries under `ROWS_TYPE`: this page's token, and the documents' numbers, names and folders. */
export function rowsPayload(documents: readonly Movable[]): string {
  return JSON.stringify({ token: pageToken, documents: documents.map(({ docNumber, name, folder }) => ({ docNumber, name, folder: folder ? { id: folder.id } : undefined })) })
}

/** The documents a drag of this page carried; nothing for data that isn't what `rowsPayload` makes, or has another token. */
export function readRows(text: string): Movable[] {
  try {
    const parsed = JSON.parse(text) as { token?: unknown; documents?: unknown } | null
    if (parsed?.token !== pageToken || !Array.isArray(parsed.documents)) return []
    return parsed.documents.flatMap((one: unknown) => {
      const { docNumber, name, folder } = (one ?? {}) as { docNumber?: unknown; name?: unknown; folder?: { id?: unknown } }
      if (typeof docNumber !== 'number' || !Number.isSafeInteger(docNumber) || typeof name !== 'string') return []
      return [typeof folder?.id === 'number' ? { docNumber, name, folder: { id: folder.id } } : { docNumber, name }]
    })
  } catch {
    return []
  }
}
