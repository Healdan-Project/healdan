// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { Movable } from '../actions/batch'

/** What a drag of rows carries under `ROWS_TYPE`: the documents' numbers, with their names for what is said of them. */
export function rowsPayload(documents: readonly Movable[]): string {
  return JSON.stringify(documents.map(({ docNumber, name }) => ({ docNumber, name })))
}

/** The documents a drag carried; nothing for data that isn't what `rowsPayload` makes. */
export function readRows(text: string): Movable[] {
  try {
    const parsed: unknown = JSON.parse(text)
    if (!Array.isArray(parsed)) return []
    return parsed.flatMap((one: unknown) => {
      const { docNumber, name } = (one ?? {}) as { docNumber?: unknown; name?: unknown }
      return typeof docNumber === 'number' && Number.isSafeInteger(docNumber) && typeof name === 'string' ? [{ docNumber, name }] : []
    })
  } catch {
    return []
  }
}
