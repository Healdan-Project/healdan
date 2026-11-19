// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { QueryClientProvider, type QueryClient } from '@tanstack/react-query'
import { RouterProvider } from '@tanstack/react-router'
import { useState } from 'react'
import type { Api } from '@healdan/api'
import { ServicesProvider, type SignedOutState } from '@healdan/shell'
import { ToastRegion } from '@healdan/ui'
import { AccessProvider, createAccess, guardApi } from './access'
import type { createAdminRouter } from './router'

export interface AppProps {
  api: Api
  queryClient: QueryClient
  signedOut: SignedOutState
  router: ReturnType<typeof createAdminRouter>
}

/**
 * The app. Every screen's calls go through one guarded client (access.tsx), so a 401 or a `403 admins-only` from any of
 * them stops the whole app, and the frame says so, whichever screen asked.
 */
export function App({ api, queryClient, signedOut, router }: AppProps) {
  const [guarded] = useState(() => {
    const access = createAccess()
    return { access, api: guardApi(api, access, signedOut) }
  })
  return (
    <ServicesProvider api={guarded.api} signedOut={signedOut}>
      <AccessProvider access={guarded.access}>
        <QueryClientProvider client={queryClient}>
          <RouterProvider router={router} />
          <ToastRegion />
        </QueryClientProvider>
      </AccessProvider>
    </ServicesProvider>
  )
}
