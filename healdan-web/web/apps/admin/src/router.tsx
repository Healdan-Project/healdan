// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQueryClient } from '@tanstack/react-query'
import { createRootRoute, createRoute, createRouter, type ErrorComponentProps, type RouterHistory } from '@tanstack/react-router'
import { ErrorPanel, parseSearch, stringifySearch } from '@healdan/shell'
import { ADMIN_BASE, routes } from './addresses'
import { Frame } from './frame/Frame'
import { BackendsView } from './views/BackendsView'
import { BackendView } from './views/BackendView'
import { ClientView, clientSearch } from './views/ClientView'
import { ClientsView, clientsSearch } from './views/ClientsView'
import { DashboardView } from './views/DashboardView'
import { GroupsView, groupsSearch } from './views/GroupsView'
import { GroupView } from './views/GroupView'
import { MattersView, mattersSearch } from './views/MattersView'
import { MatterView, matterSearch } from './views/MatterView'
import { StoresView } from './views/StoresView'
import { StoreView, storeSearch } from './views/StoreView'
import { TemplatesView } from './views/TemplatesView'
import { TemplateView } from './views/TemplateView'
import { UsersView, usersSearch } from './views/UsersView'
import { UserView } from './views/UserView'
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

// /matters: every matter, its order and filters in the address (MattersView).
const mattersRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: routes.matters,
  validateSearch: mattersSearch,
  component: function MattersRoute() {
    return <MattersView search={mattersRoute.useSearch()} />
  },
})

// /matters/$id: a matter's page, its tab in the address (MatterView); /templates, every workspace template, and
// /templates/$id, a template's page, each saying itself when the id names nothing
const matterRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: routes.matter,
  validateSearch: matterSearch,
  component: function MatterRoute() {
    return <MatterView id={matterRoute.useParams().id} search={matterRoute.useSearch()} />
  },
})

const templatesRoute = createRoute({ getParentRoute: () => rootRoute, path: routes.templates, component: TemplatesView })

const templateRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: routes.template,
  component: function TemplateRoute() {
    return <TemplateView id={templateRoute.useParams().id} />
  },
})

// /stores: every Store, and the Stores in the Trash (StoresView); /stores/$id, a Store's page, its tab and the order of its
// matters in the address (StoreView), which says itself when the id names no live Store
const storesRoute = createRoute({ getParentRoute: () => rootRoute, path: routes.stores, component: StoresView })

const storeRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: routes.store,
  validateSearch: storeSearch,
  component: function StoreRoute() {
    return <StoreView id={storeRoute.useParams().id} search={storeRoute.useSearch()} />
  },
})

// /backends: every storage backend, the files with no Store and the storage settings (BackendsView); /backends/$id, a
// backend's page (BackendView), which says itself when the id names no backend
const backendsRoute = createRoute({ getParentRoute: () => rootRoute, path: routes.backends, component: BackendsView })

const backendRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: routes.backend,
  component: function BackendRoute() {
    return <BackendView id={backendRoute.useParams().id} />
  },
})

// /users: every person, their filters and order in the address (UsersView); /users/$id, a person's page (UserView), which
// says itself when the id names nobody
const usersRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: routes.users,
  validateSearch: usersSearch,
  component: function UsersRoute() {
    return <UsersView search={usersRoute.useSearch()} />
  },
})

const userRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: routes.user,
  component: function UserRoute() {
    return <UserView id={userRoute.useParams().id} />
  },
})

// /groups: every group but the people's own, its words in the address (GroupsView); /groups/$id, a group's page with its
// members (GroupView), which says itself when the id names no group
const groupsRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: routes.groups,
  validateSearch: groupsSearch,
  component: function GroupsRoute() {
    return <GroupsView search={groupsRoute.useSearch()} />
  },
})

const groupRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: routes.group,
  component: function GroupRoute() {
    return <GroupView id={groupRoute.useParams().id} />
  },
})

const routeTree = rootRoute.addChildren([
  dashboardRoute,
  clientsRoute,
  clientRoute,
  mattersRoute,
  matterRoute,
  templatesRoute,
  templateRoute,
  usersRoute,
  userRoute,
  groupsRoute,
  groupRoute,
  storesRoute,
  storeRoute,
  backendsRoute,
  backendRoute,
])

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
