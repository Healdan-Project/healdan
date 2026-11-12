// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQueryClient } from '@tanstack/react-query'
import { createRootRoute, createRoute, createRouter, type ErrorComponentProps, type RouterHistory } from '@tanstack/react-router'
import { ErrorPanel } from './frame/ErrorPanel'
import { Frame } from './frame/Frame'
import { filterSearch, multiFilters, type Filters } from './filters/useFilters'
import { idFrom } from './queries'
import { DocResult } from './views/DocResult'
import { FolderView } from './views/FolderView'
import { NotFound } from './views/NotFound'
import { RecentView } from './views/RecentView'
import { SearchView } from './views/SearchView'
import { isSearchScope } from './search/scopes'
import { isSortColumn, type ListingOrder, type SortColumn } from './views/useListing'

// Addresses carry plain strings (?sel=10388, not ?sel="10388"), which the default JSON-aware search handling would quote.
// A filter can repeat (?type=pdf&type=word), so those come as lists; anything else repeated keeps its last value.
function parseSearch(search: string): Record<string, string | string[]> {
  const params = new URLSearchParams(search)
  const parsed: Record<string, string | string[]> = Object.fromEntries(params)
  for (const name of multiFilters) if (params.has(name)) parsed[name] = params.getAll(name)
  return parsed
}

function stringifySearch(search: Record<string, unknown>): string {
  const params = new URLSearchParams()
  for (const [name, value] of Object.entries(search)) {
    for (const one of Array.isArray(value) ? value : [value]) {
      if (one !== undefined && one !== null && one !== '') params.append(name, String(one))
    }
  }
  const text = params.toString()
  return text ? `?${text}` : ''
}

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

const routeTree = rootRoute.addChildren([indexRoute, recentRoute, foldersRoute, searchRoute, docRoute])

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

// The app lives under /app, served by AppServlet. Only the root context is supported in M6.
export function createAppRouter(history?: RouterHistory) {
  return createRouter({ routeTree, basepath: '/app', history, parseSearch, stringifySearch, defaultErrorComponent: RouteError })
}

// Links, navigate() and the search hooks are typed from the routes above.
declare module '@tanstack/react-router' {
  interface Register {
    router: ReturnType<typeof createAppRouter>
  }
}
