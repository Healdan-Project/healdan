// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQueryClient } from '@tanstack/react-query'
import { createRootRoute, createRoute, createRouter, type ErrorComponentProps, type RouterHistory } from '@tanstack/react-router'
import { ErrorPanel, parseSearch, stringifySearch } from '@healdan/shell'
import { ADMIN_BASE, routes } from './addresses'
import { Frame } from './frame/Frame'
import { ClientView, clientSearch } from './views/ClientView'
import { ClientsView, clientsSearch } from './views/ClientsView'
import { DashboardView } from './views/DashboardView'
import { MattersView, mattersSearch } from './views/MattersView'
import { NotFoundView } from './views/NotFoundView'

// The frame holds every page, and an address no page has is said in the frame, in the app's own words. A page reads only
// the parameters its route's validateSearch takes, so whatever else an address carries is ignored.
const rootRoute = createRootRoute({ component: Frame, notFoundComponent: NotFoundView })

const dashboardRoute = createRoute({ getParentRoute: () => rootRoute, path: routes.dashboard, component: DashboardView })

// /clients: every client, its words and order in the address (ClientsView); /clients/$id, a client's page, the order of
// its matters in the address (ClientView), which says itself when the id names no client.
const clientsRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: routes.clients,
  validateSearch: clientsSearch,
  component: function ClientsRoute() {
    return <ClientsView search={clientsRoute.useSearch()} />
  },
})

const clientRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: routes.client,
  validateSearch: clientSearch,
  component: function ClientRoute() {
    return <ClientView id={clientRoute.useParams().id} search={clientRoute.useSearch()} />
  },
})

// /matters: every matter, its order and filters in the address (MattersView). A matter's page, /matters/$id, is
// Phase 2's next screen; until it's routed the frame says there's no such page.
const mattersRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: routes.matters,
  validateSearch: mattersSearch,
  component: function MattersRoute() {
    return <MattersView search={mattersRoute.useSearch()} />
  },
})

const routeTree = rootRoute.addChildren([dashboardRoute, clientsRoute, clientRoute, mattersRoute])

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
