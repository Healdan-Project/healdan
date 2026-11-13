// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { focusManager, QueryClient } from '@tanstack/react-query'
import { ApiError } from '@healdan/api'

export interface QueryClientOptions {
  /** Tests turn retries off. */
  retry?: false
}

/**
 * Tells TanStack Query when the person comes back to the page: the window getting the focus back, or a hidden tab showing
 * again. TanStack Query listens only for the second, and moving from Excel back to a browser window that stayed on screen
 * fires none, so a check-out or a save made through the helper would never show (spec 10.2). A focus while the page is
 * hidden doesn't count. A tab's return fires both at once, and a request already under way isn't sent again. Gives back
 * what stops listening.
 */
export function listenForReturn(onFocus: () => void): () => void {
  const visible = () => {
    if (document.visibilityState !== 'hidden') onFocus()
  }
  // Only the window's own focus: a field's doesn't bubble here, and is no return
  const focused = (event: Event) => {
    if (event.target === event.currentTarget) visible()
  }
  window.addEventListener('focus', focused)
  document.addEventListener('visibilitychange', visible)
  return () => {
    window.removeEventListener('focus', focused)
    document.removeEventListener('visibilitychange', visible)
  }
}

// One client for the app. What's on screen is asked again each time the person comes back to the window, however fresh
// it is, as it stands in for push updates; an answer otherwise stays fresh for 30 seconds, so moving between views doesn't
// ask again. A view that mustn't ask on each return (a search, a preview of one file version) says so itself. A problem
// the server answered is not retried; a lost connection is. A 401 is the API client's to report (createApi's onSignedOut),
// for reads and changes alike.
export function createQueryClient({ retry }: QueryClientOptions = {}): QueryClient {
  focusManager.setEventListener(listenForReturn)
  return new QueryClient({
    defaultOptions: {
      queries: {
        refetchOnWindowFocus: 'always',
        staleTime: 30_000,
        retry: retry ?? ((count, error) => !(error instanceof ApiError) && count < 2),
      },
    },
  })
}
