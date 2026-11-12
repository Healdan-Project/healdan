// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { QueryClientProvider, type QueryClient } from '@tanstack/react-query'
import { render } from '@testing-library/react'
import type { ReactNode } from 'react'
import { OpenInAppProvider } from '../actions/OpenInAppState'
import { createQueryClient } from '../queryClient'
import { ServicesProvider } from '../services'
import { createSignedOut } from '../signedOut'
import { fakeApi, type FakeApi, type Routes } from './fakeApi'

/** One part of the app on its own, with the fake API and a query client, for parts that need no router. */
export function renderPreview(ui: ReactNode, routes: Routes = {}): { api: FakeApi; queryClient: QueryClient } & ReturnType<typeof render> {
  const signedOut = createSignedOut()
  // As main.tsx gives createApi the same
  const api = fakeApi(routes, signedOut.mark)
  const queryClient = createQueryClient({ retry: false })
  const view = render(
    <ServicesProvider api={api} signedOut={signedOut}>
      <QueryClientProvider client={queryClient}>
        <OpenInAppProvider>{ui}</OpenInAppProvider>
      </QueryClientProvider>
    </ServicesProvider>,
  )
  return { api, queryClient, ...view }
}

/** The bytes of a PDF as the preview route answers them */
export function pdfResponse(): Response {
  return new Response(new TextEncoder().encode('%PDF-1.4 fake'), { status: 200, headers: { 'Content-Type': 'application/pdf' } })
}

/** The answer while a preview is still being made */
export function pendingResponse(retryAfter = 5): Response {
  return new Response(null, { status: 202, headers: { 'Retry-After': String(retryAfter) } })
}
