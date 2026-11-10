// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { render } from '@testing-library/react'
import { createMemoryHistory } from '@tanstack/react-router'
import { App } from '../App'
import { createQueryClient } from '../queryClient'
import { createAppRouter } from '../router'
import { createSignedOut } from '../signedOut'
import { fakeApi, type FakeApi, type Routes } from './fakeApi'

export interface Rendered {
  api: FakeApi
  router: ReturnType<typeof createAppRouter>
  view: ReturnType<typeof render>
}

/** The whole app at an address (without the /app base), answering from the fake API. */
export async function renderApp(path = '/', routes: Routes = {}): Promise<Rendered> {
  const api = fakeApi(routes)
  const signedOut = createSignedOut()
  const queryClient = createQueryClient({ onSignedOut: signedOut.mark, retry: false })
  const router = createAppRouter(createMemoryHistory({ initialEntries: ['/app' + path] }))
  // The router resolves its first address asynchronously; wait, so the first render already shows the view.
  await router.load()
  const view = render(<App api={api} queryClient={queryClient} signedOut={signedOut} router={router} />)
  return { api, router, view }
}
