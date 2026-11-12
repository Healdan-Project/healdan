// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { QueryClient } from '@tanstack/react-query'

/**
 * Asks again for everything that shows a document's state: the document and its properties and versions, a folder's
 * list, search results and Recent; My Favorites, which names documents; and Recent Matters and Recent Clients, which a
 * change to a document of another matter can add to. Whatever changes a document (a check-out, a save of its
 * properties, a rename, a move, a filing) calls this when it's done, whether it worked or not.
 */
export function invalidateDocumentLists(queryClient: QueryClient): Promise<unknown> {
  return Promise.all(['documents', 'folders', 'search', 'favorites', 'matters', 'clients'].map((root) => queryClient.invalidateQueries({ queryKey: [root] })))
}
