// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { adminComponents } from '@healdan/api'
import { useAdminQuery } from '../queries'
import { typed, useDebounced } from './typing'

type A = adminComponents['schemas']

/** The most clients a search offers: the first by number */
export const CLIENT_SEARCH_LIMIT = 20

export interface ClientSearch {
  /** The clients found for what is typed, by number; none while nothing is */
  clients: A['Client'][]
  isError: boolean
  error: unknown
}

/**
 * The clients for a picker (the Matters list's Client filter, New Matter's Client): those whose number starts with what is
 * typed, or whose name holds it, in any case (`GET /v1/admin/clients`), asked once the typing pauses. One character is
 * enough, as a client's number can be one digit. The last answer stays while the next is asked, so the list doesn't blink.
 */
export function useClientSearch(text: string): ClientSearch {
  const q = typed(text)
  const settled = useDebounced(q)
  const found = useAdminQuery('/v1/admin/clients', {
    query: { q: settled, limit: CLIENT_SEARCH_LIMIT },
    enabled: settled !== '',
    keepPrevious: true,
    // A keystroke back and forth needn't ask twice
    staleTime: 30_000,
  })
  return { clients: q === '' ? [] : (found.data?.items ?? []), isError: found.isError, error: found.error }
}
