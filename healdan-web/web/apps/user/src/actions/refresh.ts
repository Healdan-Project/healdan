// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { QueryClient } from '@tanstack/react-query'

// Everything that shows a document's state: the document and its properties and versions, a folder's list, search results
// and Recent; My Favorites, which names documents; and Recent Matters and Recent Clients, which a change to a document of
// another matter can add to
const DOCUMENT_LISTS = ['documents', 'folders', 'search', 'favorites', 'matters', 'clients']

/**
 * Asks again for everything that shows a document's state. Whatever changes a document (a check-out, a save of its
 * properties, a rename, a move, a filing) calls this when it's done, whether it worked or not.
 */
export function invalidateDocumentLists(queryClient: QueryClient): Promise<unknown> {
  return Promise.all(DOCUMENT_LISTS.map((root) => queryClient.invalidateQueries({ queryKey: [root] })))
}

/**
 * Marks the same as stale without asking now, for a change that comes later and out of the page's sight: Open in App's
 * helper checks the document out seconds after the link, and saves to it later still. Asked now, the answer would show the
 * document free, and stay fresh for 30 seconds; marked, the return to the window asks again, as does any view moved to.
 */
export function markDocumentListsStale(queryClient: QueryClient): void {
  for (const root of DOCUMENT_LISTS) void queryClient.invalidateQueries({ queryKey: [root], refetchType: 'none' })
}
