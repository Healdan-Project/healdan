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
