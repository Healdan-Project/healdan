// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { QueryClient } from '@tanstack/react-query'
import { ApiError } from '@healdan/api'

export interface QueryClientOptions {
  /** Tests turn retries off. */
  retry?: false
}

// One client for the app: lists refresh when the window comes back (they stand in for push updates), and stay fresh for
// 30 seconds so moving between views doesn't ask again. A problem the server answered is not retried; a lost connection is.
// A 401 is the API client's to report (createApi's onSignedOut), for reads and changes alike.
export function createQueryClient({ retry }: QueryClientOptions = {}): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        refetchOnWindowFocus: true,
        staleTime: 30_000,
        retry: retry ?? ((count, error) => !(error instanceof ApiError) && count < 2),
      },
    },
  })
}
