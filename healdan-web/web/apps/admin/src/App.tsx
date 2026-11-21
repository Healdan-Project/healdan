// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { QueryClientProvider, type QueryClient } from '@tanstack/react-query'
import { RouterProvider } from '@tanstack/react-router'
import type { Api } from '@healdan/api'
import { MessagesProvider, ServicesProvider, type SignedOutState } from '@healdan/shell'
import { ToastRegion } from '@healdan/ui'
import { AccessProvider, type AccessState } from './access'
import { problemLookup } from './strings'
import type { createAdminRouter } from './router'

export interface AppProps {
  /** The guarded client (access.tsx's guardApi), made where the client is: the app is never given another */
  api: Api
  /** The access state the guarded client marks */
  access: AccessState
  queryClient: QueryClient
  signedOut: SignedOutState
  router: ReturnType<typeof createAdminRouter>
}

/**
 * The app. Every screen's calls go through the one guarded client it's given (access.tsx), so a 401 or a
 * `403 admins-only` from any of them stops the whole app, and the frame says so, whichever screen asked.
 */
export function App({ api, access, queryClient, signedOut, router }: AppProps) {
  return (
    <ServicesProvider api={api} signedOut={signedOut}>
      <AccessProvider access={access}>
        <QueryClientProvider client={queryClient}>
          <MessagesProvider lookup={problemLookup}>
            <RouterProvider router={router} />
            <ToastRegion />
          </MessagesProvider>
        </QueryClientProvider>
      </AccessProvider>
    </ServicesProvider>
  )
}
