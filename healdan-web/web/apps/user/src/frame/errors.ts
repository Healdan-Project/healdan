// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { ApiError } from '@healdan/api'
import { strings, t, type StringKey } from '@healdan/ui'

/** What to tell the person: a sentence from the catalogue, whatever went wrong. The server's own text is never shown. */
export function messageFor(error: unknown): string {
  if (error instanceof ApiError) {
    const key = `problems.${error.code}`
    // hasOwn: a code like "toString" must not find something on the object's prototype.
    if (Object.hasOwn(strings, key)) return t(key as StringKey)
  }
  return t('problems.unknown')
}

export function isStorageUnreachable(error: unknown): boolean {
  return error instanceof ApiError && error.status === 503 && error.code === 'storage-unreachable'
}

/**
 * The fields a `422 validation` names, each with its message from the catalogue (the server's own text is never shown),
 * by the property's name. A field named twice keeps its first message. Empty for any other error.
 */
export function fieldErrors(error: unknown): Map<string, string> {
  const messages = new Map<string, string>()
  if (!(error instanceof ApiError) || error.code !== 'validation') return messages
  const fields = (error.body as { fields?: unknown } | null)?.fields
  if (!Array.isArray(fields)) return messages
  for (const item of fields as { field?: unknown; code?: unknown }[]) {
    if (typeof item?.field !== 'string' || messages.has(item.field)) continue
    const key = `fields.${String(item.code)}`
    messages.set(item.field, Object.hasOwn(strings, key) ? t(key as StringKey) : t('problems.validation'))
  }
  return messages
}
