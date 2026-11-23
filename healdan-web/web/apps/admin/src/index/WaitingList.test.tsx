// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { act, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { formatDateTime } from '@healdan/ui'
import { expectNoAxeViolations } from '../test/axe'
import { problem, searchIndexRoutes } from '../test/fakeApi'
import { renderApp } from '../test/renderApp'

// axe runs once for each state drawn; the themes' colours are tokens, which test/colours.test.ts checks in both themes.

const table = () => screen.getByRole('table', { name: 'Waiting' })

describe('Waiting', () => {
  it('lists each document waiting, in the order they were filed, with its doc #, title, matter and when it last changed', async () => {
    const fake = searchIndexRoutes()
    const { api, view } = await renderApp('/search-index?tab=waiting', fake.routes)
    await within(await screen.findByRole('table', { name: 'Waiting' })).findAllByRole('rowheader')
    expect(within(table()).getAllByRole('columnheader').map((heading) => heading.textContent)).toEqual(['Doc #', 'Title', 'Matter', 'Last Changed'])
    expect(within(table()).getAllByRole('rowheader').map((header) => header.textContent)).toEqual(['# 10420', '# 10421', '# 10422', '# 10423', '# 10424'])
    const first = within(table()).getAllByRole('row')[1]!
    expect(within(first).getAllByRole('cell').map((cell) => cell.textContent)).toEqual(['Exhibit 1.pdf', '1001.1', formatDateTime('2026-11-19T10:35:00Z')])
    // Plain text: a row opens nothing
    expect(within(table()).queryAllByRole('link')).toEqual([])
    expect(within(table()).queryAllByRole('button')).toEqual([])
    // Couldn't Be Indexed's list isn't asked for while its tab isn't open
    expect(api.callsTo('GET /v1/admin/search-index/failed')).toEqual([])
    await expectNoAxeViolations(view.container)
  })

  it('says so when nothing waits', async () => {
    await renderApp('/search-index?tab=waiting', searchIndexRoutes({ waiting: [] }).routes)
    expect(await screen.findByText('No document is waiting to be indexed.')).toBeTruthy()
  })

  it("keeps the rows while a new read fails, saying it couldn't refresh (P4-R15)", async () => {
    let failing = false
    const fake = searchIndexRoutes()
    const list = fake.routes['GET /v1/admin/search-index/waiting']
    await renderApp('/search-index?tab=waiting', { ...fake.routes, 'GET /v1/admin/search-index/waiting': (request: { query?: Record<string, unknown> }) => (failing ? problem(500, 'server-error') : list(request)) })
    await within(await screen.findByRole('table', { name: 'Waiting' })).findAllByRole('rowheader')
    failing = true
    await act(async () => void window.dispatchEvent(new Event('focus')))
    expect(await screen.findAllByText("Couldn't refresh this, so it shows what was read last.")).not.toHaveLength(0)
    expect(within(table()).getAllByRole('rowheader')).toHaveLength(5)
  })

  it('says why a first read failed, with Try Again', async () => {
    const fake = searchIndexRoutes()
    await renderApp('/search-index?tab=waiting', { ...fake.routes, 'GET /v1/admin/search-index/waiting': problem(500, 'server-error') })
    expect(await screen.findByText('Something went wrong.')).toBeTruthy()
    expect(screen.queryByRole('table', { name: 'Waiting' })).toBeNull()
  })
})
