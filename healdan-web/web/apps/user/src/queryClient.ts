// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { QueryCache, QueryClient } from '@tanstack/react-query'
import { ApiError } from '@healdan/api'

export interface QueryClientOptions {
  /** Called when any request answers 401. */
  onSignedOut: () => void
  /** Tests turn retries off. */
  retry?: false
}

// One client for the app: lists refresh when the window comes back (they stand in for push updates), and stay fresh for
// 30 seconds so moving between views doesn't ask again. A problem the server answered is not retried; a lost connection is.
export function createQueryClient({ onSignedOut, retry }: QueryClientOptions): QueryClient {
  return new QueryClient({
    queryCache: new QueryCache({
      onError(error) {
        if (error instanceof ApiError && error.status === 401) onSignedOut()
      },
    }),
    defaultOptions: {
      queries: {
        refetchOnWindowFocus: true,
        staleTime: 30_000,
        retry: retry ?? ((count, error) => !(error instanceof ApiError) && count < 2),
      },
    },
  })
}
