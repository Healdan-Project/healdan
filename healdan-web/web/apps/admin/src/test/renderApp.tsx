// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { render } from '@testing-library/react'
import { createMemoryHistory } from '@tanstack/react-router'
import type { QueryClient } from '@tanstack/react-query'
import { createQueryClient, createSignedOut } from '@healdan/shell'
import { createAccess, guardApi } from '../access'
import { App } from '../App'
import { ADMIN_BASE } from '../addresses'
import { createAdminRouter } from '../router'
import { fakeApi, type FakeApi, type Routes } from './fakeApi'

export interface Rendered {
  /** The fake behind the guarded client the app was given, for the calls that reached it */
  api: FakeApi
  router: ReturnType<typeof createAdminRouter>
  view: ReturnType<typeof render>
  queryClient: QueryClient
}

/** The whole app at an address (without the /admin base), answering from the fake API. */
export async function renderApp(path = '/', routes: Routes = {}): Promise<Rendered> {
  const signedOut = createSignedOut()
  // As main.tsx gives createApi the same, and guards the client where it's made
  const api = fakeApi(routes, signedOut.mark)
  const access = createAccess()
  const queryClient = createQueryClient({ retry: false })
  const router = createAdminRouter(createMemoryHistory({ initialEntries: [ADMIN_BASE + path] }))
  // The router resolves its first address asynchronously; wait, so the first render already shows the view.
  await router.load()
  const view = render(<App api={guardApi(api, access, signedOut)} access={access} queryClient={queryClient} signedOut={signedOut} router={router} />)
  return { api, router, view, queryClient }
}
