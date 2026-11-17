// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { Movable } from '../actions/batch'

/**
 * Names this page's drags: a drag carries it, and a drop moves documents only when it holds this one, so a drag that began in
 * another page (another tab of the app, or any page that happens to use the type) moves nothing. 128 random bits, from
 * getRandomValues, which every page has: randomUUID exists only at a secure address, and this runs as the app loads.
 */
export const pageToken = Array.from(globalThis.crypto.getRandomValues(new Uint8Array(16)), (byte) => byte.toString(16).padStart(2, '0')).join('')

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

/** The key of the row that follows the documents' rows in the list drawn now, else the one before them, else none */
export function rowAfter(docNumbers: readonly number[]): string | undefined {
  const gone = new Set(docNumbers.map((n) => `d${n}`))
  const keys = Array.from(globalThis.document.querySelectorAll('[role="grid"] [data-row-key]'), (row) => row.getAttribute('data-row-key') ?? '')
  const last = keys.findLastIndex((key) => gone.has(key))
  if (last < 0) return undefined
  return keys.slice(last + 1).find((key) => !gone.has(key)) ?? keys.slice(0, last).findLast((key) => !gone.has(key))
}

/**
 * Focuses a row once it has been drawn again, or the list when there is no such row (as the dialog that had the focus
 * gives it back first). With `gone`, the row keys of documents that may have left the list, only once none of them is
 * drawn: where they stay, as a released document does in a folder, the focus stays where it is.
 */
export function focusLater(key: string | undefined, gone: readonly string[] = []) {
  let tries = 0
  const drawn = (row: string) => globalThis.document.querySelector(`[role="grid"] [data-row-key="${row}"]`) !== null
  const attempt = () => {
    const target = gone.some(drawn)
      ? null
      : ((key ? globalThis.document.querySelector<HTMLElement>(`[data-row-key="${key}"]`) : null) ?? (key ? null : globalThis.document.querySelector<HTMLElement>('[role="grid"]')))
    if (target) target.focus()
    else if (++tries < 20) setTimeout(attempt, 50)
  }
  setTimeout(attempt, 0)
}
