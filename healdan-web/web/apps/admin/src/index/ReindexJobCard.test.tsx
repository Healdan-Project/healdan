// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { formatDateTime } from '@healdan/ui'
import { expectNoAxeViolations } from '../test/axe'
import { problem, searchIndexDoneJob, searchIndexJob, searchIndexRoutes, searchIndexWaitingJob, type Routes, type SearchIndexRoutesOptions } from '../test/fakeApi'
import { renderApp } from '../test/renderApp'

// axe runs once for each state drawn; the themes' colours are tokens, which test/colours.test.ts checks in both themes.

const card = (title: string) => screen.getByRole('region', { name: title })
const settle = () => act(() => new Promise((resolve) => setTimeout(resolve, 20)))

async function open(options: SearchIndexRoutesOptions = {}, over: Routes = {}) {
  const fake = searchIndexRoutes(options)
  const rendered = await renderApp('/search-index', { ...fake.routes, ...over })
  await screen.findByText('1,277')
  return { ...rendered, fake }
}

describe("The reindexes' cards", () => {
  it('shows the running reindex first, with who started it, its progress and about how long is left, then those waiting their turn', async () => {
    const { view } = await open()
    const running = await screen.findByRole('region', { name: 'Reindexing Matter 1001.1 Charter Dispute' })
    const waiting = card('Waiting to Reindex Store Client Work')
    expect(running.compareDocumentPosition(waiting) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(within(running).getByText(`Started by Sam Okafor, ${formatDateTime('2026-11-19T10:42:00Z')}`)).toBeTruthy()
    // Done, failed and skipped together, of the total
    const bar = within(running).getByRole('progressbar', { name: 'Documents' })
    expect(bar.getAttribute('aria-valuenow')).toBe('212')
    expect(bar.getAttribute('aria-valuemax')).toBe('412')
    expect(within(running).getByText('212 of 412 documents · about 6 minutes left · search keeps working while it runs')).toBeTruthy()
    expect(within(waiting).getByText('Waiting its turn, as one reindex runs at a time: 1,004 documents.')).toBeTruthy()
    expect(within(waiting).queryByRole('progressbar')).toBeNull()
    // Each has Cancel, named with it; a reindex that ended before the page opened is the History tab's
    expect(within(running).getByRole('button', { name: 'Cancel Reindexing Matter 1001.1 Charter Dispute' })).toBeTruthy()
    expect(within(waiting).getByRole('button', { name: 'Cancel Waiting to Reindex Store Client Work' })).toBeTruthy()
    expect(screen.queryByRole('region', { name: 'Reindexed 3 Documents' })).toBeNull()
    await expectNoAxeViolations(view.container)
  })

  it("says it's waiting for the index while the index can't be written, with no estimate", async () => {
    await open({ jobs: [searchIndexJob({ stalledSince: '2026-11-19T10:44:00Z', estimateSecondsLeft: null })] })
    const running = await screen.findByRole('region', { name: 'Reindexing Matter 1001.1 Charter Dispute' })
    expect(within(running).getByText(`212 of 412 documents · waiting for the index, which can't be written to since ${formatDateTime('2026-11-19T10:44:00Z')}: it tries again shortly`)).toBeTruthy()
  })

  it("says it makes no progress while the server's reindex worker fails, with no estimate", async () => {
    await open({ jobs: [searchIndexJob({ failingSince: '2026-11-19T10:45:00Z', estimateSecondsLeft: null })] })
    const running = await screen.findByRole('region', { name: 'Reindexing Matter 1001.1 Charter Dispute' })
    expect(
      within(running).getByText(`212 of 412 documents · making no progress since ${formatDateTime('2026-11-19T10:45:00Z')}, as the server can't go on with it: it tries again every 30 seconds, and Background Jobs says why`),
    ).toBeTruthy()
  })

  it("says how many it skipped on storage that couldn't be reached, while it runs and once it has ended (P6-R23)", async () => {
    const { fake } = await open({ jobs: [searchIndexJob({ skipped: 5, unreachable: 4 })] })
    const running = await screen.findByRole('region', { name: 'Reindexing Matter 1001.1 Charter Dispute' })
    expect(within(running).getByText("215 of 412 documents · 4 on storage that couldn't be reached · about 6 minutes left · search keeps working while it runs")).toBeTruthy()
    fake.setJob(searchIndexJob({ state: 'done', done: 405, failed: 2, skipped: 5, unreachable: 4, ended: '2026-11-19T10:48:00Z', estimateSecondsLeft: null }))
    await act(async () => void window.dispatchEvent(new Event('focus')))
    const ended = await screen.findByRole('region', { name: 'Reindexed Matter 1001.1 Charter Dispute' })
    expect(within(ended).getByText("405 reindexed, 2 couldn't be indexed, 5 skipped (4 on storage that couldn't be reached). Those that couldn't be indexed are on the Couldn't Be Indexed tab.")).toBeTruthy()
  })

  it('shows none while nothing waits or runs', async () => {
    await open({ jobs: [searchIndexDoneJob] })
    await settle()
    expect(screen.queryAllByRole('progressbar')).toEqual([])
    expect(screen.queryByRole('button', { name: /^Cancel / })).toBeNull()
  })

  it("keeps the cards while a new read fails, saying it couldn't refresh (P4-R15)", async () => {
    let failing = false
    const fake = searchIndexRoutes()
    const list = fake.routes['GET /v1/admin/reindex']
    await renderApp('/search-index', { ...fake.routes, 'GET /v1/admin/reindex': (request: { query?: Record<string, unknown> }) => (failing ? problem(500, 'server-error') : list(request)) })
    await screen.findByRole('region', { name: 'Reindexing Matter 1001.1 Charter Dispute' })
    failing = true
    await act(async () => void window.dispatchEvent(new Event('focus')))
    expect(await screen.findAllByText("Couldn't refresh this, so it shows what was read last.")).not.toHaveLength(0)
    expect(card('Reindexing Matter 1001.1 Charter Dispute')).toBeTruthy()
  })
})

describe('Cancel', () => {
  it('asks first, naming the reindex, and Cancel sends nothing', async () => {
    const { api } = await open()
    const running = await screen.findByRole('region', { name: 'Reindexing Matter 1001.1 Charter Dispute' })
    await userEvent.click(within(running).getByRole('button', { name: /^Cancel/ }))
    const question = await screen.findByRole('alertdialog', { name: 'Cancel the Reindex of Matter 1001.1 Charter Dispute?' })
    expect(within(question).getByText(/The documents it hasn't reached keep the entries they have/)).toBeTruthy()
    await expectNoAxeViolations(question)
    await userEvent.click(within(question).getByRole('button', { name: 'Cancel' }))
    await waitFor(() => expect(screen.queryByRole('alertdialog', { name: /^Cancel the Reindex/ })).toBeNull())
    expect(api.callsTo('DELETE /v1/admin/reindex/*')).toEqual([])
  })

  it('by keyboard alone, asks a running reindex to stop: its card says so, Cancel goes, and the focus goes to its title', async () => {
    const { api } = await open()
    const running = await screen.findByRole('region', { name: 'Reindexing Matter 1001.1 Charter Dispute' })
    within(running).getByRole('button', { name: /^Cancel/ }).focus()
    await userEvent.keyboard('{Enter}')
    const question = await screen.findByRole('alertdialog')
    // Tab from Cancel to Cancel Reindex, and press it
    within(question).getByRole('button', { name: 'Cancel' }).focus()
    await userEvent.tab()
    expect(document.activeElement).toBe(within(question).getByRole('button', { name: 'Cancel Reindex' }))
    await userEvent.keyboard('{Enter}')
    await waitFor(() => expect(screen.queryByRole('alertdialog', { name: /^Cancel the Reindex/ })).toBeNull())
    expect(api.callsTo('DELETE /v1/admin/reindex/7')).toHaveLength(1)
    expect(await screen.findByText('Asked the reindex of Matter 1001.1 Charter Dispute to stop')).toBeTruthy()
    await waitFor(() => expect(within(running).getByText(/stopping once the documents it has started on are done/)).toBeTruthy())
    expect(within(running).queryByRole('button', { name: /^Cancel/ })).toBeNull()
    await waitFor(() => expect(document.activeElement).toBe(within(running).getByRole('heading', { name: 'Reindexing Matter 1001.1 Charter Dispute' })))
  })

  it("cancels a waiting reindex at once: its card says it was cancelled until it's closed, and Close's focus goes to the next card", async () => {
    const { api } = await open()
    const waiting = await screen.findByRole('region', { name: 'Waiting to Reindex Store Client Work' })
    await userEvent.click(within(waiting).getByRole('button', { name: /^Cancel/ }))
    const question = await screen.findByRole('alertdialog', { name: 'Cancel the Reindex of Store Client Work?' })
    expect(within(question).getByText("It hasn't started, so nothing in the index changes.")).toBeTruthy()
    const dashboard = api.callsTo('GET /v1/admin/dashboard').length
    await userEvent.click(within(question).getByRole('button', { name: 'Cancel Reindex' }))
    const ended = await screen.findByRole('region', { name: 'Cancelled Reindexing Store Client Work' })
    // The Dashboard's answer, which the left pane's counts are, is asked for again, as it shows the reindex job
    await waitFor(() => expect(api.callsTo('GET /v1/admin/dashboard').length).toBeGreaterThan(dashboard))
    expect(within(ended).getByText('Cancelled before it started, so nothing in the index changed.')).toBeTruthy()
    // It keeps its place, after the running one
    expect(card('Reindexing Matter 1001.1 Charter Dispute').compareDocumentPosition(ended) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    const close = within(ended).getByRole('button', { name: 'Close Cancelled Reindexing Store Client Work' })
    close.focus()
    await userEvent.keyboard('{Enter}')
    await waitFor(() => expect(screen.queryByRole('region', { name: 'Cancelled Reindexing Store Client Work' })).toBeNull())
    await waitFor(() => expect(document.activeElement).toBe(screen.getByRole('heading', { name: 'Reindexing Matter 1001.1 Charter Dispute' })))
  })

  it("keeps a refusal in the question, in the rule's words, and reads the cards again first", async () => {
    const { api } = await open({}, { 'DELETE /v1/admin/reindex/*': problem(409, 'refused', 'This reindex has ended.') })
    const running = await screen.findByRole('region', { name: 'Reindexing Matter 1001.1 Charter Dispute' })
    const reads = api.callsTo('GET /v1/admin/reindex').length
    await userEvent.click(within(running).getByRole('button', { name: /^Cancel/ }))
    const question = await screen.findByRole('alertdialog')
    await userEvent.click(within(question).getByRole('button', { name: 'Cancel Reindex' }))
    expect((await within(question).findByRole('alert')).textContent).toBe('This reindex has ended.')
    expect(api.callsTo('GET /v1/admin/reindex').length).toBeGreaterThan(reads)
  })
})

describe('A reindex that ends while the page is open', () => {
  it("shows how it went until it's closed: how many it reindexed, how many couldn't be indexed and how many it skipped", async () => {
    const { fake, view } = await open({ jobs: [searchIndexJob()] })
    await screen.findByRole('region', { name: 'Reindexing Matter 1001.1 Charter Dispute' })
    fake.setJob(searchIndexJob({ state: 'done', done: 408, failed: 3, skipped: 1, ended: '2026-11-19T10:48:00Z', estimateSecondsLeft: null }))
    await act(async () => void window.dispatchEvent(new Event('focus')))
    const ended = await screen.findByRole('region', { name: 'Reindexed Matter 1001.1 Charter Dispute' })
    expect(within(ended).getByText("408 reindexed, 3 couldn't be indexed, 1 skipped. Those that couldn't be indexed are on the Couldn't Be Indexed tab.")).toBeTruthy()
    expect(within(ended).queryByRole('progressbar')).toBeNull()
    await expectNoAxeViolations(view.container)
    await userEvent.click(within(ended).getByRole('button', { name: 'Close Reindexed Matter 1001.1 Charter Dispute' }))
    await waitFor(() => expect(screen.queryByRole('region', { name: 'Reindexed Matter 1001.1 Charter Dispute' })).toBeNull())
    // Nothing else is there, so the focus goes to the page's heading
    await waitFor(() => expect(document.activeElement).toBe(screen.getByRole('heading', { level: 1, name: 'Search Index' })))
  })

  it("says what a running reindex cancelled had done before it stopped, its card where it was, before those waiting", async () => {
    const { fake } = await open()
    await screen.findByRole('region', { name: 'Waiting to Reindex Store Client Work' })
    fake.setJob(searchIndexJob({ state: 'cancelled', ended: '2026-11-19T10:48:00Z', estimateSecondsLeft: null }))
    await act(async () => void window.dispatchEvent(new Event('focus')))
    const ended = await screen.findByRole('region', { name: 'Cancelled Reindexing Matter 1001.1 Charter Dispute' })
    expect(within(ended).getByText("Cancelled: 208 reindexed, 2 couldn't be indexed, 2 skipped before it stopped. Those that couldn't be indexed are on the Couldn't Be Indexed tab.")).toBeTruthy()
    expect(ended.compareDocumentPosition(card('Waiting to Reindex Store Client Work')) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it('is gone once the page is left', async () => {
    const { fake, router } = await open({ jobs: [searchIndexJob(), searchIndexWaitingJob] })
    await screen.findByRole('region', { name: 'Reindexing Matter 1001.1 Charter Dispute' })
    fake.setJob(searchIndexJob({ state: 'done', done: 410, ended: '2026-11-19T10:48:00Z', estimateSecondsLeft: null }))
    await act(async () => void window.dispatchEvent(new Event('focus')))
    await screen.findByRole('region', { name: 'Reindexed Matter 1001.1 Charter Dispute' })
    act(() => router.history.push('/admin/jobs'))
    await screen.findByRole('heading', { level: 1, name: 'Background Jobs' })
    act(() => router.history.back())
    await screen.findByRole('region', { name: 'Waiting to Reindex Store Client Work' })
    expect(screen.queryByRole('region', { name: 'Reindexed Matter 1001.1 Charter Dispute' })).toBeNull()
  })
})
