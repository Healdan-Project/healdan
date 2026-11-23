// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { adminComponents } from '@healdan/api'
import { formatDateTime } from '@healdan/ui'
import { INDEXER_POLL_MS, REINDEX_POLL_MS } from '../index/queries'
import { SOON_MS } from '../monitoring/jobQueries'
import { expectNoAxeViolations } from '../test/axe'
import { jobOf, problem, searchIndexDoneJob, searchIndexJob, searchIndexOf, searchIndexRoutes, type Routes, type SearchIndexRoutesOptions } from '../test/fakeApi'
import { renderApp } from '../test/renderApp'
import { slowAnswer } from '../test/slowAnswer'
import { hidePage, returnToPage } from '../test/visibility'

type A = adminComponents['schemas']

// axe runs once for each state drawn. jsdom draws no colours, so the two themes' colours aren't checked here: every colour
// is a token, which test/colours.test.ts checks on each surface of both themes, and the phase's browser spec runs axe in
// the light theme and the dark one.

const pane = () => screen.getByRole('navigation', { name: 'Navigation' })
const tiles = () => within(screen.getByText('1,277').closest('ul')!).getAllByRole('listitem')
/** A moment for what was asked to be answered and drawn, on a real timeout, as the polls' intervals may be faked */
const settle = () => act(() => new Promise((resolve) => setTimeout(resolve, 20)))
const waitSeconds = async (seconds: number) => {
  await act(async () => void vi.advanceTimersByTime(seconds * 1000))
  await settle()
}

async function openSearchIndex(options: SearchIndexRoutesOptions = {}, over: Routes = {}, path = '/search-index') {
  const fake = searchIndexRoutes(options)
  const rendered = await renderApp(path, { ...fake.routes, ...over })
  await screen.findByText('1,277')
  return { ...rendered, fake }
}

describe('The Search Index', () => {
  it('opens at /admin/search-index under its heading, in the pane under Search, with its count of documents that couldn\'t be indexed', async () => {
    const { view } = await openSearchIndex()
    expect(screen.getByRole('heading', { level: 1, name: 'Search Index' })).toBeTruthy()
    const search = within(pane()).getByRole('region', { name: 'Search' })
    const link = await within(search).findByRole('link', { name: 'Search Index, 2 need attention' })
    expect(link.getAttribute('aria-current')).toBe('page')
    expect(link.getAttribute('href')).toBe('/admin/search-index')
    await expectNoAxeViolations(view.container)
  })

  it("shows Indexed with the index's entries and size, Waiting, Couldn't Be Indexed, the health and the Indexer's runs", async () => {
    await openSearchIndex()
    const texts = tiles()
      .slice(0, 4)
      .map((tile) => tile.textContent)
    expect(texts).toEqual([
      '1,277Indexed1,290 entries · 412 MB',
      '5Waiting',
      "2Couldn't Be Indexed",
      `Needs Attention5 waiting, 2 failedLast run ${formatDateTime('2026-11-19T10:30:00Z')} · next ${formatDateTime('2026-11-19T11:00:00Z')}`,
    ])
  })

  it("leaves out what the server couldn't read, and says the index is healthy and the Indexer running", async () => {
    await openSearchIndex({
      index: searchIndexOf({
        failed: 0,
        entries: null,
        bytes: null,
        health: { status: 'ok', detail: '5 waiting, none failed' },
        indexer: { job: jobOf({ running: true, canRun: false, lastStart: '2026-11-19T10:42:00Z' }), runRefusal: 'This job is running already.' },
      }),
    })
    expect(tiles()[0]!.textContent).toBe('1,277Indexed')
    expect(tiles()[3]!.textContent).toBe(`Index HealthyThe Indexer is running, since ${formatDateTime('2026-11-19T10:42:00Z')}`)
  })

  it('names the first two tabs with their counts, opens on Couldn\'t Be Indexed, and keeps the tab in the address, in place of the one before', async () => {
    const { router } = await openSearchIndex()
    const tabs = screen.getByRole('tablist', { name: 'Search Index' })
    expect(within(tabs).getAllByRole('tab').map((tab) => tab.textContent)).toEqual(["Couldn't Be Indexed (2)", 'Waiting (5)', 'History'])
    expect(within(tabs).getByRole('tab', { selected: true }).textContent).toBe("Couldn't Be Indexed (2)")
    const length = router.history.length
    await userEvent.click(within(tabs).getByRole('tab', { name: 'Waiting (5)' }))
    await waitFor(() => expect(router.state.location.search).toEqual({ tab: 'waiting' }))
    expect(router.history.length).toBe(length)
    expect(await screen.findByRole('table', { name: 'Waiting' })).toBeTruthy()
    await userEvent.click(within(tabs).getByRole('tab', { name: "Couldn't Be Indexed (2)" }))
    await waitFor(() => expect(router.state.location.search).toEqual({}))
  })

  it('opens on the tab the address names', async () => {
    await openSearchIndex({}, {}, '/search-index?tab=history')
    expect(screen.getByRole('tab', { selected: true }).textContent).toBe('History')
  })

  it("opens on the first tab for one it doesn't know", async () => {
    await openSearchIndex({}, {}, '/search-index?tab=nonsense&reindex=sideways&id=x')
    expect(screen.getByRole('tab', { selected: true }).textContent).toBe("Couldn't Be Indexed (2)")
  })

  it('Reindex… sets the choice in the address, as a step of its own, so Back takes it away (P6-R18)', async () => {
    const { router } = await openSearchIndex({}, {}, '/search-index?tab=waiting')
    await userEvent.click(screen.getByRole('button', { name: 'Reindex…' }))
    await waitFor(() => expect(router.state.location.search).toEqual({ tab: 'waiting', reindex: 'matter' }))
    act(() => router.history.back())
    await waitFor(() => expect(router.state.location.search).toEqual({ tab: 'waiting' }))
  })

  it('closes the dialog Reindex… opened by stepping back, so Back then leaves no second Search Index behind', async () => {
    const { router } = await openSearchIndex()
    await act(() => router.navigate({ href: '/search-index?tab=waiting' }))
    await userEvent.click(screen.getByRole('button', { name: 'Reindex…' }))
    await screen.findByRole('dialog', { name: 'Reindex' })
    await userEvent.click(within(screen.getByRole('dialog', { name: 'Reindex' })).getByRole('button', { name: 'Cancel' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(router.state.location.search).toEqual({ tab: 'waiting' })
    act(() => router.history.back())
    await waitFor(() => expect(router.state.location.search).toEqual({}))
  })

  it('says why a first read of the heading failed, with Try Again, and still shows the rest', async () => {
    let failing = true
    const fake = searchIndexRoutes()
    await renderApp('/search-index', { ...fake.routes, 'GET /v1/admin/search-index': () => (failing ? problem(500, 'server-error') : searchIndexOf()) })
    expect(await screen.findByText('Something went wrong.')).toBeTruthy()
    expect(await screen.findByRole('table', { name: "Couldn't Be Indexed" })).toBeTruthy()
    failing = false
    await userEvent.click(screen.getAllByRole('button', { name: 'Try Again' })[0]!)
    expect(await screen.findByText('1,277')).toBeTruthy()
  })

  it("keeps the heading while a new read fails, saying it couldn't refresh, with Try Again (P4-R15)", async () => {
    let failing = false
    const fake = searchIndexRoutes()
    await renderApp('/search-index', { ...fake.routes, 'GET /v1/admin/search-index': () => (failing ? problem(500, 'server-error') : searchIndexOf()) })
    await screen.findByText('1,277')
    failing = true
    await act(async () => void window.dispatchEvent(new Event('focus')))
    expect(await screen.findAllByText("Couldn't refresh this, so it shows what was read last.")).not.toHaveLength(0)
    expect(screen.getByText('1,277')).toBeTruthy()
  })
})

describe('Run Indexer Now', () => {
  it('runs the Indexer at once, holding the button on its way, then shows it as answered, greyed out with the reason, and asks the counts, the tabs and the Dashboard again', async () => {
    const slow = slowAnswer<A['Indexer']>()
    const { api, view, fake } = await openSearchIndex({}, { 'POST /v1/admin/search-index/run': () => slow.answered })
    const before = { dashboard: api.callsTo('GET /v1/admin/dashboard').length, failed: api.callsTo('GET /v1/admin/search-index/failed').length }
    const run = screen.getByRole('button', { name: 'Run Indexer Now' })
    run.focus()
    await userEvent.keyboard('{Enter}')
    expect(api.callsTo('POST /v1/admin/search-index/run')).toHaveLength(1)
    expect(run.getAttribute('data-pending')).toBe('true')
    const answer = { job: jobOf({ running: true, canRun: false, lastStart: '2026-11-19T10:42:00Z' }), runRefusal: 'This job is running already.' }
    fake.setIndex(searchIndexOf({ indexer: answer }))
    await act(async () => slow.answer(answer))
    expect(await screen.findByText('Asked the Indexer to run now')).toBeTruthy()
    await waitFor(() => expect(run.hasAttribute('disabled')).toBe(true))
    expect(tiles()[3]!.textContent).toContain('The Indexer is running')
    const reason = screen.getByText('This job is running already.')
    expect(run.getAttribute('aria-describedby')).toBe(reason.id)
    await waitFor(() => expect(api.callsTo('GET /v1/admin/dashboard').length).toBeGreaterThan(before.dashboard))
    expect(api.callsTo('GET /v1/admin/search-index/failed').length).toBeGreaterThan(before.failed)
    // Greyed out under the focus once the press is done, the button gives the focus to the page's heading
    await waitFor(() => expect(document.activeElement).toBe(screen.getByRole('heading', { level: 1, name: 'Search Index' })))
    await expectNoAxeViolations(view.container)
  })

  it("is greyed out from the start, with the server's reason, while the Indexer can't run", async () => {
    const { api } = await openSearchIndex({ index: searchIndexOf({ indexer: { job: jobOf({ enabled: false, canRun: false }), runRefusal: 'This job is disabled.' } }) })
    const run = screen.getByRole('button', { name: 'Run Indexer Now' })
    expect(run.getAttribute('aria-describedby')).toBe(screen.getByText('This job is disabled.').id)
    await userEvent.click(run)
    expect(api.callsTo('POST /v1/admin/search-index/run')).toHaveLength(0)
  })

  it("says a refusal in the rule's words while the Indexer is as it was, and takes it away once a new read shows it changed", async () => {
    const { fake } = await openSearchIndex({}, { 'POST /v1/admin/search-index/run': problem(409, 'refused', 'The scheduled tasks are switched off on this server.') })
    await userEvent.click(screen.getByRole('button', { name: 'Run Indexer Now' }))
    expect((await screen.findByRole('alert')).textContent).toBe('The scheduled tasks are switched off on this server.')
    fake.setIndex(searchIndexOf({ indexer: { job: jobOf({ lastStart: '2026-11-19T11:00:00Z', nextRun: '2026-11-19T11:30:00Z' }), runRefusal: null } }))
    await act(async () => void window.dispatchEvent(new Event('focus')))
    await waitFor(() => expect(screen.queryByRole('alert')).toBeNull())
  })

  it('says a refusal once: when the heading read again gives the reason, the reason beside the greyed-out button says it alone', async () => {
    const { fake } = await openSearchIndex({}, { 'POST /v1/admin/search-index/run': problem(409, 'refused', 'This job is running already.') })
    fake.setIndex(searchIndexOf({ indexer: { job: jobOf({ running: true, canRun: false }), runRefusal: 'This job is running already.' } }))
    await userEvent.click(screen.getByRole('button', { name: 'Run Indexer Now' }))
    await waitFor(() => expect(screen.getByRole('button', { name: 'Run Indexer Now' }).hasAttribute('disabled')).toBe(true))
    expect(screen.getAllByText('This job is running already.')).toHaveLength(1)
    expect(screen.queryByRole('alert')).toBeNull()
  })
})

describe('Asking again (P6-R19)', () => {
  async function polling(options: SearchIndexRoutesOptions, over: Routes = {}) {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
    const rendered = await openSearchIndex(options, over)
    await settle()
    return rendered
  }
  const reads = (api: { callsTo: (pattern: string) => unknown[] }, pattern: string) => api.callsTo(pattern).length

  it('asks for the reindexes every 5 seconds while one runs, and the counts and the open tab every 30, while the page is visible', async () => {
    const { api } = await polling({})
    const at = { jobs: reads(api, 'GET /v1/admin/reindex'), index: reads(api, 'GET /v1/admin/search-index'), failed: reads(api, 'GET /v1/admin/search-index/failed') }
    await waitSeconds(REINDEX_POLL_MS / 1000)
    expect(reads(api, 'GET /v1/admin/reindex')).toBe(at.jobs + 1)
    expect(reads(api, 'GET /v1/admin/search-index')).toBe(at.index)
    for (let i = 0; i < 5; i++) await waitSeconds(REINDEX_POLL_MS / 1000)
    expect(reads(api, 'GET /v1/admin/reindex')).toBe(at.jobs + 6)
    expect(reads(api, 'GET /v1/admin/search-index')).toBe(at.index + 1)
    expect(reads(api, 'GET /v1/admin/search-index/failed')).toBe(at.failed + 1)
    // The tabs not open aren't asked for
    expect(reads(api, 'GET /v1/admin/search-index/waiting')).toBe(0)
  })

  it('asks nothing on a timer while no reindex waits or runs, and asks again when the person comes back', async () => {
    const { api } = await polling({ jobs: [searchIndexDoneJob] })
    const count = api.calls.length
    await waitSeconds(120)
    expect(api.calls).toHaveLength(count)
    await act(async () => void window.dispatchEvent(new Event('focus')))
    await settle()
    expect(reads(api, 'GET /v1/admin/search-index')).toBe(2)
  })

  it('asks nothing while the page is hidden', async () => {
    const { api } = await polling({})
    hidePage()
    const count = reads(api, 'GET /v1/admin/reindex')
    await waitSeconds(60)
    expect(reads(api, 'GET /v1/admin/reindex')).toBe(count)
    returnToPage()
    await settle()
    expect(reads(api, 'GET /v1/admin/reindex')).toBeGreaterThan(count)
  })

  it("asks for the counts, the tabs and the Dashboard at once when a reindex ends, and shows how it ended until it's closed", async () => {
    const { api, fake } = await polling({ jobs: [searchIndexJob()] })
    const at = { index: reads(api, 'GET /v1/admin/search-index'), dashboard: reads(api, 'GET /v1/admin/dashboard'), failed: reads(api, 'GET /v1/admin/search-index/failed') }
    fake.setJob(searchIndexJob({ state: 'done', done: 408, failed: 3, skipped: 1, ended: '2026-11-19T10:48:00Z', estimateSecondsLeft: null }))
    await waitSeconds(REINDEX_POLL_MS / 1000)
    expect(await screen.findByRole('heading', { name: 'Reindexed Matter 1001.1 Charter Dispute' })).toBeTruthy()
    expect(reads(api, 'GET /v1/admin/search-index')).toBe(at.index + 1)
    expect(reads(api, 'GET /v1/admin/search-index/failed')).toBe(at.failed + 1)
    expect(reads(api, 'GET /v1/admin/dashboard')).toBeGreaterThan(at.dashboard)
    // Nothing waits or runs now, so nothing more is asked on a timer
    const count = api.calls.length
    await waitSeconds(60)
    expect(api.calls).toHaveLength(count)
  })

  it('asks for the heading every 5 seconds while the Indexer runs, with no reindex, then the tabs and the Dashboard once its run ends', async () => {
    const runningIndexer = { job: jobOf({ running: true, canRun: false, canStop: true, lastStart: '2026-11-19T10:42:00Z' }), runRefusal: 'This job is running already.' }
    const { api, fake } = await polling({ jobs: [searchIndexDoneJob], index: searchIndexOf({ indexer: runningIndexer }) })
    const at = { index: reads(api, 'GET /v1/admin/search-index'), failed: reads(api, 'GET /v1/admin/search-index/failed'), dashboard: reads(api, 'GET /v1/admin/dashboard') }
    await waitSeconds(INDEXER_POLL_MS / 1000)
    expect(reads(api, 'GET /v1/admin/search-index')).toBe(at.index + 1)
    await waitSeconds(INDEXER_POLL_MS / 1000)
    expect(reads(api, 'GET /v1/admin/search-index')).toBe(at.index + 2)
    // Only the heading: the open tab waits for the run's end
    expect(reads(api, 'GET /v1/admin/search-index/failed')).toBe(at.failed)
    expect(reads(api, 'GET /v1/admin/reindex')).toBe(1)

    // The run ends: the next read shows it, the tab and the Dashboard are asked again at once, and the button comes back
    fake.setIndex(searchIndexOf({ waiting: 0, indexer: { job: jobOf({ lastStart: '2026-11-19T10:42:00Z', lastEnd: '2026-11-19T10:43:10Z' }), runRefusal: null } }))
    await waitSeconds(INDEXER_POLL_MS / 1000)
    expect(reads(api, 'GET /v1/admin/search-index')).toBe(at.index + 3)
    await waitFor(() => expect(reads(api, 'GET /v1/admin/search-index/failed')).toBe(at.failed + 1))
    expect(reads(api, 'GET /v1/admin/dashboard')).toBeGreaterThan(at.dashboard)
    await waitFor(() => expect(screen.getByRole('button', { name: 'Run Indexer Now' }).hasAttribute('disabled')).toBe(false))
    // Nothing runs now, so nothing more is asked on a timer
    const count = api.calls.length
    await waitSeconds(60)
    expect(api.calls).toHaveLength(count)
  })

  it("asks for the heading once 2 seconds after Run Indexer Now, as the answer may not show the run yet, then every 5 while it runs", async () => {
    // The server's answer, before the run shows
    const { api, fake } = await polling({ jobs: [searchIndexDoneJob] }, { 'POST /v1/admin/search-index/run': () => ({ job: jobOf(), runRefusal: null }) satisfies A['Indexer'] })
    await userEvent.click(screen.getByRole('button', { name: 'Run Indexer Now' }))
    await screen.findByText('Asked the Indexer to run now')
    await settle()
    const at = reads(api, 'GET /v1/admin/search-index')
    fake.setIndex(searchIndexOf({ indexer: { job: jobOf({ running: true, canRun: false, canStop: true, lastStart: '2026-11-19T10:42:00Z' }), runRefusal: 'This job is running already.' } }))
    await waitSeconds(SOON_MS / 1000)
    expect(reads(api, 'GET /v1/admin/search-index')).toBe(at + 1)
    await waitFor(() => expect(tiles()[3]!.textContent).toContain('The Indexer is running'))
    expect(screen.getByRole('button', { name: 'Run Indexer Now' }).hasAttribute('disabled')).toBe(true)
    // Once: then every 5 seconds while the run goes on
    await waitSeconds(SOON_MS / 1000)
    expect(reads(api, 'GET /v1/admin/search-index')).toBe(at + 1)
    await waitSeconds((INDEXER_POLL_MS - SOON_MS) / 1000)
    expect(reads(api, 'GET /v1/admin/search-index')).toBe(at + 2)
  })

  it('stops when the page is left', async () => {
    const { api, router } = await polling({})
    act(() => router.history.push('/admin/no-such-page'))
    await screen.findByRole('heading', { level: 1, name: 'Page Not Found' })
    const count = api.calls.length
    await waitSeconds(60)
    expect(api.calls).toHaveLength(count)
  })
})
