// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

/** Where AdminAppServlet serves the app, the router's basepath. Only the root context is supported. */
export const ADMIN_BASE = '/admin'

/**
 * Every page's address under ADMIN_BASE (spec 6.3), as the router's paths and Links name them: `$id` is a page's id. No
 * segment is one CE's filters or login.jsp treat as their own (addresses.test.ts checks them, and every routed one).
 */
export const routes = {
  dashboard: '/',
  clients: '/clients',
  client: '/clients/$id',
  matters: '/matters',
  matter: '/matters/$id',
  templates: '/templates',
  template: '/templates/$id',
  stores: '/stores',
  store: '/stores/$id',
  backends: '/backends',
  backend: '/backends/$id',
  users: '/users',
  user: '/users/$id',
  groups: '/groups',
  group: '/groups/$id',
  errors: '/errors',
  serverLog: '/server-log',
  audit: '/audit',
  jobs: '/jobs',
  searchIndex: '/search-index',
} as const
