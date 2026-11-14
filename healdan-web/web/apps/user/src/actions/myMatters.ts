// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQueryClient } from '@tanstack/react-query'
import { apiPath } from '@healdan/api'
import { toast } from '@healdan/ui'
import { messageFor } from '../frame/errors'
import { keys } from '../queries'
import { useApi } from '../services'

/**
 * A change to My Matters (`PUT` adds the matter, `DELETE` takes it out), then the list asked again; a refusal is said in a
 * toast. The left pane's + and ⋯ and a matter's right-click menu all make it.
 */
export function useMyMattersChange() {
  const api = useApi()
  const queryClient = useQueryClient()
  return async (method: 'PUT' | 'DELETE', folderId: number) => {
    try {
      await api.send(method, apiPath`/v1/me/matters/${folderId}`)
      // Recent Clients are those of recent matters and My Matters
      await Promise.all([queryClient.invalidateQueries({ queryKey: keys.myMatters }), queryClient.invalidateQueries({ queryKey: keys.recentClients })])
    } catch (error) {
      toast(messageFor(error))
    }
  }
}
