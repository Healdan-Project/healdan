// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { focusManager, QueryClient, type Query } from '@tanstack/react-query'
import { ApiError } from '@healdan/api'

export interface QueryClientOptions {
  /** Tests turn retries off. */
  retry?: false
}

/**
 * The `meta` of a query that shows a document's state: the document, its properties and versions, a folder's list and
 * Recent. Each return to the window asks it again however fresh it is, as it stands in for push updates (spec 10.2);
 * anything else asks again on a return only once its answer is over 30 seconds old.
 */
export const ASK_ON_RETURN = { askOnReturn: true }

/**
 * Tells TanStack Query when the person comes back to the page: the window getting the focus back, or a hidden tab showing
 * again. TanStack Query listens only for the second, and moving from Excel back to a browser window that stayed on screen
 * fires none, so a check-out or a save made through the helper would never show (spec 10.2). The focus coming back to
 * the page from a frame in it (an email's body) fires the window's focus too, and is no return. A tab's return fires both
 * at once, and a request already under way isn't sent again. Gives back what stops listening.
 */
export function listenForReturn(onReturn: () => void): () => void {
  // Where the focus went, which the browser says only once the window's blur is over
  let inFrame = false
  let pending: ReturnType<typeof setTimeout> | undefined
  const blurred = () => {
    pending = setTimeout(() => {
      inFrame = document.activeElement instanceof HTMLIFrameElement
    })
  }
  const focused = () => {
    if (inFrame) inFrame = false
    else onReturn()
  }
  window.addEventListener('blur', blurred)
  window.addEventListener('focus', focused)
  document.addEventListener('visibilitychange', onReturn)
  return () => {
    clearTimeout(pending)
    window.removeEventListener('blur', blurred)
    window.removeEventListener('focus', focused)
    document.removeEventListener('visibilitychange', onReturn)
  }
}

// A list scrolled past its first page would ask for every page it has at each return, so it waits out its 30 seconds
function askedOnReturn(query: Query): boolean {
  const pages = (query.state.data as { pages?: unknown[] } | undefined)?.pages
  return query.meta?.askOnReturn === true && (pages?.length ?? 1) <= 1
}

// One client for the app. An answer stays fresh for 30 seconds, so moving between views doesn't ask again, and a return
// to the window asks again for what's on screen and older than that. What shows a document's state (ASK_ON_RETURN) is
// marked stale first, so the return asks for it however fresh it is. A problem the server answered is not retried; a
// lost connection is. A 401 is the API client's to report (createApi's onSignedOut), for reads and changes alike.
export function createQueryClient({ retry }: QueryClientOptions = {}): QueryClient {
  const client = new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 30_000,
        retry: retry ?? ((count, error) => !(error instanceof ApiError) && count < 2),
      },
    },
  })
  focusManager.setEventListener((onFocus) =>
    listenForReturn(() => {
      void client.invalidateQueries({ predicate: askedOnReturn, refetchType: 'none' })
      onFocus()
    }),
  )
  return client
}
