// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { createRootRoute, createRoute, createRouter, type RouterHistory } from '@tanstack/react-router'
import { t } from '@healdan/ui'
import { Frame } from './frame/Frame'
import { NotFound } from './views/NotFound'
import { NotYet } from './views/NotYet'
import { RecentDocuments } from './views/RecentDocuments'
import { RecentKindView } from './views/RecentKind'

// Addresses carry plain strings (?sel=10388, not ?sel="10388"), which the default JSON-aware search handling would quote.
function parseSearch(search: string): Record<string, string> {
  return Object.fromEntries(new URLSearchParams(search))
}

function stringifySearch(search: Record<string, unknown>): string {
  const params = new URLSearchParams()
  for (const [name, value] of Object.entries(search)) {
    if (value !== undefined && value !== null && value !== '') params.set(name, String(value))
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

const indexRoute = createRoute({ getParentRoute: () => rootRoute, path: '/', component: RecentDocuments })

const recentRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: 'recent/$kind',
  component: function RecentRoute() {
    return <RecentKindView kind={recentRoute.useParams().kind} />
  },
})

const foldersRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: 'folders/$id',
  component: () => <NotYet title={t('notYet.folder')} />,
})

const searchRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: 'search',
  validateSearch: (search: Record<string, unknown>): { scope?: string; q?: string; type?: string } => ({
    scope: text(search.scope),
    q: text(search.q),
    type: text(search.type),
  }),
  component: () => <NotYet title={t('notYet.search')} />,
})

// /doc/10388 and /doc/10388.2: the # handle (§10.1).
const docRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: 'doc/$ref',
  component: () => <NotYet title={t('notYet.document')} />,
})

const routeTree = rootRoute.addChildren([indexRoute, recentRoute, foldersRoute, searchRoute, docRoute])

// The app lives under /app, served by AppServlet. Only the root context is supported in M6.
export function createAppRouter(history?: RouterHistory) {
  return createRouter({ routeTree, basepath: '/app', history, parseSearch, stringifySearch })
}
