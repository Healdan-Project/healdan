// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { QueryClient } from '@tanstack/react-query'

/**
 * Asks again for everything that shows a document's state: the document and its properties and versions, a folder's
 * list, search results and Recent. Whatever changes a document (a check-out, a save of its properties, a rename or a
 * move) calls this when it's done, whether it worked or not.
 */
export function invalidateDocumentLists(queryClient: QueryClient): Promise<unknown> {
  return Promise.all(['documents', 'folders', 'search'].map((root) => queryClient.invalidateQueries({ queryKey: [root] })))
}
