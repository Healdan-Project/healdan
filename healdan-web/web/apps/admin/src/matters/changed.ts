// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { QueryClient } from '@tanstack/react-query'
import type { adminComponents } from '@healdan/api'
import { adminKey, adminKeys } from '../queries'
import { storesChanged } from '../stores/queries'
import { workspaceKeys } from '../workspace/queries'

type A = adminComponents['schemas']

/**
 * What a matter's change reaches besides its page: the lists of matters, the clients' pages, the top bar's find, and the
 * Stores, which count their matters
 */
function othersChanged(queryClient: QueryClient, id: number) {
  void queryClient.invalidateQueries({ queryKey: adminKeys.matters, predicate: ({ queryKey }) => queryKey[2] !== id })
  void queryClient.invalidateQueries({ queryKey: adminKeys.clients })
  void queryClient.invalidateQueries({ queryKey: adminKey('/v1/admin/find') })
  storesChanged(queryClient)
}

/** A matter as the server answered a change: its page has the answer at once, and every list that shows it is asked again */
export function matterChanged(queryClient: QueryClient, saved: A['Matter']) {
  queryClient.setQueryData(workspaceKeys.matter(saved.id), saved)
  othersChanged(queryClient, saved.id)
  // A rename renames its folder, and a new owner changes who can use it
  void queryClient.invalidateQueries({ queryKey: workspaceKeys.tree(saved.folderId) })
  void queryClient.invalidateQueries({ queryKey: workspaceKeys.folders })
}

/** A matter gone to the Trash: its page is forgotten, so nothing asks for it again, and every list is asked again */
export function matterDeleted(queryClient: QueryClient, id: number) {
  queryClient.removeQueries({ queryKey: workspaceKeys.matter(id), exact: true })
  othersChanged(queryClient, id)
}
