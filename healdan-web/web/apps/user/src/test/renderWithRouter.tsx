// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { render } from '@testing-library/react'
import { createMemoryHistory, createRootRoute, createRoute, createRouter, RouterProvider } from '@tanstack/react-router'
import type { ReactNode } from 'react'

/** One part under a router that has the app's /folders/$id address, for a part that holds links and nothing else of the app. */
export async function renderWithRouter(ui: ReactNode) {
  const root = createRootRoute({ component: () => <>{ui}</> })
  const index = createRoute({ getParentRoute: () => root, path: '/' })
  const folders = createRoute({ getParentRoute: () => root, path: 'folders/$id' })
  const router = createRouter({
    routeTree: root.addChildren([index, folders]),
    basepath: '/app',
    history: createMemoryHistory({ initialEntries: ['/app/'] }),
  })
  await router.load()
  return render(<RouterProvider router={router} />)
}
