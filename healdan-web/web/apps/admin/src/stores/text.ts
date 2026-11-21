// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { adminComponents } from '@healdan/api'
import { formatList, formatSize, type Status } from '@healdan/ui'
import { filesOf } from '../storage/text'
import { plural, t } from '../strings'

type A = adminComponents['schemas']

/** "Wasabi EU (Encrypted)", or "Original": a backend, as a Store names it */
export function backendLabel(backend: Pick<A['StoreBackend'], 'name' | 'encrypted'>): string {
  return backend.encrypted ? t('admin.stores.encrypted', { name: backend.name }) : backend.name
}

/**
 * The state of a Store's files, as the Stores list and its page say it: Up to Date; "3 files stuck"; "188 files waiting
 * (11.3 MB)"; or both, "1 file stuck, 12 files waiting", with the dot that goes before the words
 */
export function filesState(pending: A['PendingFiles']): { status: Status; words: string } {
  const stuck = pending.stuck > 0 ? t('admin.stores.files.stuck', { files: filesOf(pending.stuck) }) : undefined
  if (pending.waiting === 0) return stuck === undefined ? { status: 'ok', words: t('admin.stores.files.upToDate') } : { status: 'attention', words: stuck }
  if (stuck !== undefined) return { status: 'attention', words: t('admin.stores.files.both', { stuck, waiting: t('admin.stores.files.waiting', { files: filesOf(pending.waiting) }) }) }
  // Moving is no trouble: the files open from where they are meanwhile
  return { status: 'off', words: t('admin.stores.files.waitingSize', { files: filesOf(pending.waiting), size: formatSize(pending.waitingBytes) }) }
}

/** Who can read a Store, by name, as a line: "Everyone, Helen Ashcombe", or Administrators Only */
export function readersOf(store: Pick<A['StoreInfo'], 'readers'>): string {
  return store.readers.length === 0 ? t('admin.stores.adminsOnly') : formatList(store.readers.map((reader) => reader.name))
}

/**
 * "6 matters" in the middle of a sentence: the firm's own word, counted, as the shared plural gives it ("6 Matters"),
 * in lower case, as {matterLower} is
 */
export function mattersInSentence(count: number): string {
  return plural('matters', count).toLowerCase()
}
