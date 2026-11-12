// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { QueryClientProvider, type QueryClient } from '@tanstack/react-query'
import { RouterProvider } from '@tanstack/react-router'
import type { Api } from '@healdan/api'
import { ToastRegion } from '@healdan/ui'
import type { createAppRouter } from './router'
import { ServicesProvider } from './services'
import type { SignedOutState } from './signedOut'

export interface AppProps {
  api: Api
  queryClient: QueryClient
  signedOut: SignedOutState
  router: ReturnType<typeof createAppRouter>
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
