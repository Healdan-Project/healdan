// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { Query, QueryClient } from '@tanstack/react-query'
import type { components } from '@healdan/api'

// Everything that shows a document's state: the document and its properties and versions, a folder's list, search results
// and Recent; My Favorites, which names documents; Recent Matters and Recent Clients, which a change to a document of
// another matter can add to; and the Trash, which a delete adds to and a restore takes from
const DOCUMENT_LISTS = ['documents', 'folders', 'search', 'favorites', 'matters', 'clients', 'trash']

/**
 * Asks again for everything that shows a document's state. Whatever changes a document (a check-out, a save of its
 * properties, a rename, a move, a filing) calls this when it's done, whether it worked or not. A delete, a rename or a
 * new file (a check-in, a new version) that went through names its documents in `changed`, and a filed email's message
 * that shows one as an attachment's copy (Saved as # n) is asked again too: the server pairs each attachment with the
 * documents filed from the email by name and size, and leaves out deleted ones. A move changes neither, wherever the
 * copy goes. Only those, as each read of a message reads the whole email on the server, and without waiting for them,
 * so what follows the change (its toast, the focus) doesn't wait for an email. `'any'` is for a folder deleted or
 * restored, whose documents the page can't name: every message that shows a copy is asked again.
 */
export function invalidateDocumentLists(queryClient: QueryClient, changed: readonly number[] | 'any' = []): Promise<unknown> {
  const lists = DOCUMENT_LISTS.map((root) => queryClient.invalidateQueries({ queryKey: [root] }))
  if (changed === 'any' || changed.length > 0) {
    const named = new Set(changed === 'any' ? [] : changed)
    // Partial: only data that has attachments is looked at, whatever else comes to sit under 'messages'
    const showsOne = (query: Query) =>
      (query.state.data as Partial<components['schemas']['MessageView']> | undefined)?.attachments?.some((one) => one.docNumber !== null && (changed === 'any' || named.has(one.docNumber))) ?? false
    void queryClient.invalidateQueries({ queryKey: ['messages'], predicate: showsOne })
  }
  return Promise.all(lists)
}

// Everything that shows a folder's name: the folder, its lists and the tree's subfolders (all under 'folders'), search
// results, My Favorites, and the documents, whose views carry their folder's path. A matter's or a client's list names none.
const FOLDER_NAMES = ['folders', 'search', 'favorites', 'documents']

/** Asks again for everything that shows a folder's name, once a rename has changed it (a refusal changed nothing) */
export function invalidateFolderNames(queryClient: QueryClient): Promise<unknown> {
  return Promise.all(FOLDER_NAMES.map((root) => queryClient.invalidateQueries({ queryKey: [root] })))
}

/**
 * Marks the same as stale without asking now, for a change that comes later and out of the page's sight: Open in App's
 * helper checks the document out seconds after the link, and saves to it later still. Asked now, the answer would show the
 * document free, and stay fresh for 30 seconds; marked, the return to the window asks again, as does any view moved to.
 * My Matters' folders are left as they are: a document's state doesn't change them, and a tree with many open would ask
 * for each.
 */
export function markDocumentListsStale(queryClient: QueryClient): void {
  const predicate = (query: Query) => query.queryKey[2] !== 'subfolders'
  for (const root of DOCUMENT_LISTS) void queryClient.invalidateQueries({ queryKey: [root], predicate, refetchType: 'none' })
}
