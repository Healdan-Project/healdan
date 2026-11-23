// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import type { adminComponents } from '@healdan/api'
import { formatDateTime } from '@healdan/ui'
import { expectNoAxeViolations } from '../test/axe'
import { problem, searchIndexEvent, searchIndexEventPage, searchIndexRoutes, type Routes } from '../test/fakeApi'
import { renderApp } from '../test/renderApp'

type A = adminComponents['schemas']

// axe runs once for each state drawn; the themes' colours are tokens, which test/colours.test.ts checks in both themes.

const reindexes = () => screen.getByRole('table', { name: 'Reindexes' })
const events = () => screen.getByRole('table', { name: 'Index Events' })
const rowsOf = (table: HTMLElement) => within(table).getAllByRole('row').slice(1).map((row) => Array.from(row.children, (cell) => cell.textContent))

async function openHistory(over: Routes = {}) {
  const fake = searchIndexRoutes()
  const rendered = await renderApp('/search-index?tab=history', { ...fake.routes, ...over })
  await screen.findByRole('table', { name: 'Reindexes' })
  return rendered
}

/** The events' route, answering each cursor with its page from `pages`, the first page under '' */
function byCursor(pages: Record<string, A['IndexHistoryPage']>): Routes {
  return { 'GET /v1/admin/search-index/history': ({ query }: { query?: Record<string, unknown> }) => pages[String(query?.cursor ?? '')] ?? problem(400, 'bad-request') }
}

describe('History', () => {
  it('lists the reindexes, the newest first, with who started each, when, how many it tries and how it went, then the index events', async () => {
    const { view } = await openHistory()
    expect(within(reindexes()).getAllByRole('columnheader').map((heading) => heading.textContent)).toEqual(['Reindex', 'Started By', 'Started', 'Documents', 'Outcome'])
    expect(rowsOf(reindexes())).toEqual([
      ['Store Client Work', 'Helen Ashcombe', formatDateTime('2026-11-19T10:50:00Z'), '1,004', 'Waiting Its Turn'],
      ['Matter 1001.1 Charter Dispute', 'Sam Okafor', formatDateTime('2026-11-19T10:42:00Z'), '412', "Running: 208 reindexed, 2 couldn't be indexed, 2 skipped"],
      ['3 Documents', 'Tom Reeve', formatDateTime('2026-11-12T09:00:00Z'), '3', "Done: 2 reindexed, 1 couldn't be indexed"],
    ])
    const table = await screen.findByRole('table', { name: 'Index Events' })
    expect(within(table).getAllByRole('columnheader').map((heading) => heading.textContent)).toEqual(['Time', 'Doc #', 'Title', 'Matter', 'Event', 'Reason', 'Who'])
    expect(rowsOf(table)).toEqual([
      [formatDateTime('2026-11-19T10:40:00Z'), '# 10401', 'Charter Party.pdf', '1001.1', 'Indexed', '', 'Sam Okafor'],
      [formatDateTime('2026-11-19T08:32:00Z'), '# 10398', 'Heads of Terms.pdf', '1003.1', "Couldn't Be Indexed", 'The file is encrypted', 'System'],
      [formatDateTime('2026-11-19T08:31:00Z'), '# 10412', 'Witness Statement.pdf', '1001.1', "Couldn't Be Indexed", 'The file is encrypted', 'System'],
    ])
    expect(screen.getByText('No older events.')).toBeTruthy()
    await expectNoAxeViolations(view.container)
  })

  it("says who did it as not recorded when the row names no one, and a reason as text", async () => {
    const hostile = '<b>bold</b>'
    const { view } = await openHistory({ 'GET /v1/admin/search-index/history': searchIndexEventPage([searchIndexEvent({ who: null, reason: hostile })]) })
    const table = await screen.findByRole('table', { name: 'Index Events' })
    expect(rowsOf(table)[0]!.slice(5)).toEqual([hostile, 'Not recorded'])
    expect(view.container.querySelector('b')).toBeNull()
  })

  it('reads on by cursor: a short page with a cursor is followed by itself, then Show More offers more, saying how far back it has read', async () => {
    const pages = {
      '': searchIndexEventPage([searchIndexEvent({ id: 1 })], 'c1', '2026-11-18T00:00:00Z'),
      c1: searchIndexEventPage([], 'c2', '2026-11-17T00:00:00Z'),
      c2: searchIndexEventPage([], 'c3', '2026-11-16T00:00:00Z'),
      c3: searchIndexEventPage([], 'c4', '2026-11-15T00:00:00Z'),
      c4: searchIndexEventPage([searchIndexEvent({ id: 2, when: '2026-11-14T08:00:00Z' })]),
    }
    const { api } = await openHistory(byCursor(pages))
    const more = await screen.findByRole('button', { name: 'Show More' })
    expect(api.callsTo('GET /v1/admin/search-index/history').map((call) => call.query?.cursor)).toEqual([undefined, 'c1', 'c2', 'c3'])
    expect(screen.getByText(`Read back to ${formatDateTime('2026-11-15T00:00:00Z')}`)).toBeTruthy()
    more.focus()
    await userEvent.keyboard('{Enter}')
    await waitFor(() => expect(rowsOf(events())).toHaveLength(2))
    expect(await screen.findByText('No older events.')).toBeTruthy()
    // Show More went with the end, and its focus to what says so
    await waitFor(() => expect(document.activeElement).toBe(screen.getByText('No older events.')))
  })

  it('says when nothing was found back to where it has read', async () => {
    await openHistory(byCursor({ '': searchIndexEventPage([], 'c1', '2026-11-18T00:00:00Z'), c1: searchIndexEventPage([], 'c2', '2026-11-17T00:00:00Z'), c2: searchIndexEventPage([], 'c3', '2026-11-16T00:00:00Z'), c3: searchIndexEventPage([], 'c4', '2026-11-15T00:00:00Z') }))
    expect(await screen.findByText(`Nothing found back to ${formatDateTime('2026-11-15T00:00:00Z')}. Show More reads further back.`)).toBeTruthy()
  })

  it('says so when no reindex has been started and nothing has been indexed', async () => {
    const fake = searchIndexRoutes({ jobs: [], events: [] })
    await renderApp('/search-index?tab=history', fake.routes)
    expect(await screen.findByText('No reindex has been started.')).toBeTruthy()
    expect(await screen.findByText('Nothing has been indexed yet.')).toBeTruthy()
  })
})
