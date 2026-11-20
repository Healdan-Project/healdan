// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQueryClient } from '@tanstack/react-query'
import { createRootRoute, createRoute, createRouter, type ErrorComponentProps, type RouterHistory } from '@tanstack/react-router'
import { parseSearch, stringifySearch } from '@healdan/shell'
import { APP_BASE } from './addresses'
import { ErrorPanel } from './frame/ErrorPanel'
import { Frame } from './frame/Frame'
import { filterSearch, multiFilters, type Filters } from './filters/useFilters'
import { idFrom } from './queries'
import { DocResult } from './views/DocResult'
import { FolderView } from './views/FolderView'
import { NotFound } from './views/NotFound'
import { CheckedOutView } from './views/CheckedOutView'
import { RecentView } from './views/RecentView'
import { SearchView } from './views/SearchView'
import { TrashView } from './views/TrashView'
import { isTrashSort, isTrashView, type TrashSort, type TrashViewName } from './actions/trash'
import { isSearchScope } from './search/scopes'
import { isSortColumn, type ListingOrder, type SortColumn } from './views/useListing'

const text = (value: unknown): string | undefined => (typeof value === 'string' && value !== '' ? value : undefined)

// The selected document's number is in every address (§10.1).
const rootRoute = createRootRoute({
  component: Frame,
  notFoundComponent: NotFound,
  validateSearch: (search: Record<string, unknown>): { sel?: string } => ({
    sel: typeof search.sel === 'string' && /^\d+$/.test(search.sel) ? search.sel : undefined,
  }),
})

const indexRoute = createRoute({ getParentRoute: () => rootRoute, path: '/', component: () => <RecentView kind="documents" /> })

const recentRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: 'recent/$kind',
  component: function RecentRoute() {
    return <RecentView kind={recentRoute.useParams().kind} />
  },
})

const checkedOutRoute = createRoute({ getParentRoute: () => rootRoute, path: 'checked-out', component: CheckedOutView })

// A folder keeps its sort in the address, as Back and a pasted link should show it the same way.
const foldersRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: 'folders/$id',
  validateSearch: (search: Record<string, unknown>): { sort?: SortColumn; order?: ListingOrder } & Partial<Filters> => ({
    sort: isSortColumn(search.sort) ? search.sort : undefined,
    order: search.order === 'asc' || search.order === 'desc' ? search.order : undefined,
    ...filterSearch(search),
  }),
  component: function FolderRoute() {
    const { id } = foldersRoute.useParams()
    const { sort, order } = foldersRoute.useSearch()
    const folderId = idFrom(id)
    if (folderId === undefined) return <NotFound />
    // A view of its own for each folder, so nothing chosen in one is carried to the next
    return <FolderView key={folderId} folderId={folderId} sort={sort} order={order} />
  },
})

const searchRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: 'search',
  validateSearch: (search: Record<string, unknown>): { scope?: string; q?: string; sort?: SortColumn; order?: ListingOrder } & Partial<Filters> => ({
    scope: text(search.scope),
    q: text(search.q),
    sort: isSortColumn(search.sort) ? search.sort : undefined,
    order: search.order === 'asc' || search.order === 'desc' ? search.order : undefined,
    ...filterSearch(search),
  }),
  component: function SearchRoute() {
    const { scope, q, sort, order } = searchRoute.useSearch()
    // A scope the API doesn't know is the default, Documents, as an address without one is
    const where = isSearchScope(scope) ? scope : 'documents'
    // A view of its own for each search, so a selection made in one isn't carried to the next; filters and sorting
    // change the same search and keep it.
    return <SearchView key={`${where}\u0000${q ?? ''}`} scope={where} q={q} sort={sort} order={order} />
  },
})

// /doc/10388 and /doc/10388.2: the # handle (§10.1).
const docRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: 'doc/$ref',
  component: function DocRoute() {
    // A view of its own for each address, so what one shows isn't carried to the next
    const { ref } = docRoute.useParams()
    return <DocResult key={ref} reference={ref} />
  },
})

// /trash: the person's own Trash, and for an administrator, with ?view=, everyone's or what was removed. A view the address
// doesn't name, or names wrongly, is Mine; the view itself shows Mine to anyone who isn't an administrator.
const trashRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: 'trash',
  validateSearch: (search: Record<string, unknown>): { view?: TrashViewName; sort?: TrashSort; order?: ListingOrder } => ({
    view: isTrashView(search.view) ? search.view : undefined,
    sort: isTrashSort(search.sort) ? search.sort : undefined,
    order: search.order === 'asc' || search.order === 'desc' ? search.order : undefined,
  }),
  component: function TrashRoute() {
    const { view = 'mine', sort, order } = trashRoute.useSearch()
    // A view of its own for each of Mine, Everyone's and Removed, so a selection made in one isn't carried to the next
    return <TrashView key={view} view={view} sort={sort} order={order} />
  },
})

const routeTree = rootRoute.addChildren([indexRoute, recentRoute, checkedOutRoute, foldersRoute, searchRoute, docRoute, trashRoute])

/**
 * A view that failed as it drew (spec 10.7): the catalogue's short message and Try Again, which asks the server again
 * for what the views show and draws the view anew. Never the error's own text.
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

// The app lives under /app (APP_BASE), served by AppServlet. Only the root context is supported in M6. Addresses carry
// plain strings (@healdan/shell's parseSearch), and a filter can repeat (?type=pdf&type=word), so those come as lists.
export function createAppRouter(history?: RouterHistory) {
  return createRouter({
    routeTree,
    basepath: APP_BASE,
    history,
    parseSearch: (search) => parseSearch(search, multiFilters),
    stringifySearch,
    defaultErrorComponent: RouteError,
  })
}

// Links, navigate() and the search hooks are typed from the routes above.
declare module '@tanstack/react-router' {
  interface Register {
    router: ReturnType<typeof createAppRouter>
  }
}
