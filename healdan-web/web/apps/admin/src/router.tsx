// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQueryClient } from '@tanstack/react-query'
import { createRootRoute, createRoute, createRouter, type ErrorComponentProps, type RouterHistory } from '@tanstack/react-router'
import { ErrorPanel, parseSearch, stringifySearch } from '@healdan/shell'
import { ADMIN_BASE, routes } from './addresses'
import { t } from './strings'

// A page reads only the parameters its route's validateSearch takes, so whatever else an address carries is ignored.
const rootRoute = createRootRoute()

// The app's name where the Dashboard will be, until the frame and the Dashboard come (Task 11)
function Placeholder() {
  return (
    <main>
      <h1>{t('admin.app.name')}</h1>
    </main>
  )
}

const dashboardRoute = createRoute({ getParentRoute: () => rootRoute, path: routes.dashboard, component: Placeholder })

const routeTree = rootRoute.addChildren([dashboardRoute])

/**
 * A view that failed as it drew: the catalogue's short message and Try Again, which asks the server again for what the
 * views show and draws the view anew. Never the error's own text.
 */
export function RouteError({ error, reset }: ErrorComponentProps) {
  const queryClient = useQueryClient()
  return (
    <ErrorPanel
      error={error}
      onRetry={() => {
        void queryClient.invalidateQueries()
        reset()
      }}
    />
  )
}

// The app lives under /admin (ADMIN_BASE), served by AdminAppServlet. Addresses carry plain strings, as the User app's do
// (@healdan/shell's parseSearch); no parameter repeats yet.
export function createAdminRouter(history?: RouterHistory) {
  return createRouter({
    routeTree,
    basepath: ADMIN_BASE,
    history,
    parseSearch: (search) => parseSearch(search),
    stringifySearch,
    defaultErrorComponent: RouteError,
  })
}

// Links, navigate() and the search hooks are typed from the routes above.
declare module '@tanstack/react-router' {
  interface Register {
    router: ReturnType<typeof createAdminRouter>
  }
}
