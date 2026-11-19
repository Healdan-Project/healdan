// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { QueryClientProvider, type QueryClient } from '@tanstack/react-query'
import { RouterProvider } from '@tanstack/react-router'
import type { Api } from '@healdan/api'
import { ServicesProvider, type SignedOutState } from '@healdan/shell'
import { ToastRegion } from '@healdan/ui'
import type { createAdminRouter } from './router'

export interface AppProps {
  api: Api
  queryClient: QueryClient
  signedOut: SignedOutState
  router: ReturnType<typeof createAdminRouter>
}

export function App({ api, queryClient, signedOut, router }: AppProps) {
  return (
    <ServicesProvider api={api} signedOut={signedOut}>
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
        <ToastRegion />
      </QueryClientProvider>
    </ServicesProvider>
  )
}
