// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { createMemoryHistory } from '@tanstack/react-router'
import { describe, expect, it } from 'vitest'
import { ADMIN_BASE, routes } from './addresses'
import { createAdminRouter } from './router'

// CE's UrlRewriteFilter isn't anchored at the start of the path, and login.jsp drops some return paths, so no address
// uses one of these segments, or one starting `info`, and none ends in a slash but /admin/ itself (spec 6.3).
const FORBIDDEN = ['login', 'setup', 'frontend', 'license', 'sessions', 'mobile', 'view', 'webform', 'sc', 'ace']

// The extensions AdminAppServlet's TYPES holds: a last segment with one is a file there, not an address of the app
const BUILD_EXTENSIONS = ['html', 'js', 'mjs', 'css', 'svg', 'png', 'woff2', 'json', 'txt', 'map']

function problemsOf(address: string): string[] {
  const problems: string[] = []
  for (const segment of address.split('/')) {
    if (FORBIDDEN.includes(segment.toLowerCase())) problems.push(`${address} has the segment ${segment}`)
    const extension = /\.([^./]+)$/.exec(segment)?.[1]?.toLowerCase()
    if (extension !== undefined && BUILD_EXTENSIONS.includes(extension)) problems.push(`${address} has a segment ending in a build extension: ${segment}`)
    if (segment.toLowerCase().startsWith('info')) problems.push(`${address} has a segment starting info: ${segment}`)
  }
  if (address.endsWith('/') && address !== `${ADMIN_BASE}/`) problems.push(`${address} ends in a slash`)
  return problems
}

describe('The Admin app\'s addresses', () => {
  it('no address uses a forbidden segment', () => {
    const router = createAdminRouter(createMemoryHistory({ initialEntries: ['/admin/'] }))
    const routed = Object.keys(router.routesByPath).map((path) => ADMIN_BASE + path)
    const named = Object.values(routes).map((path) => ADMIN_BASE + path)
    expect(routed.length).toBeGreaterThan(0)
    expect([...routed, ...named].flatMap(problemsOf)).toEqual([])
  })

  it('the check itself finds each kind of address the spec forbids', () => {
    expect(problemsOf('/admin/sessions')).toHaveLength(1)
    expect(problemsOf('/admin/users/$id/login')).toHaveLength(1)
    expect(problemsOf('/admin/information')).toHaveLength(1)
    expect(problemsOf('/admin/users/')).toHaveLength(1)
    expect(problemsOf('/admin/users.json')).toHaveLength(1)
    expect(problemsOf('/admin/server-log.JS')).toHaveLength(1)
    expect(problemsOf('/admin/')).toEqual([])
  })

  it('names every address of spec 6.3, the Dashboard at /admin/', () => {
    expect(Object.values(routes).map((path) => ADMIN_BASE + path).sort()).toEqual(
      [
        '/admin/',
        '/admin/clients',
        '/admin/clients/$id',
        '/admin/matters',
        '/admin/matters/$id',
        '/admin/templates',
        '/admin/templates/$id',
        '/admin/stores',
        '/admin/stores/$id',
        '/admin/backends',
        '/admin/backends/$id',
        '/admin/users',
        '/admin/users/$id',
        '/admin/groups',
        '/admin/groups/$id',
        '/admin/errors',
        '/admin/server-log',
        '/admin/audit',
        '/admin/jobs',
        '/admin/search-index',
      ].sort(),
    )
    expect(routes.dashboard).toBe('/')
  })
})
