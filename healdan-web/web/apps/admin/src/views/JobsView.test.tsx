// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { act, fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { adminComponents } from '@healdan/api'
import { formatDateTime } from '@healdan/ui'
import { SOON_MS } from '../monitoring/jobQueries'
import { expectNoAxeViolations } from '../test/axe'
import { jobFieldProblem, jobOf, jobOfHealdan, jobRoutes, jobRows, problem, type JobRoutesOptions, type Routes } from '../test/fakeApi'
import { hidePage, returnToPage } from '../test/visibility'
import { renderApp } from '../test/renderApp'
import { slowAnswer } from '../test/slowAnswer'

type A = adminComponents['schemas']

const pane = () => screen.getByRole('navigation', { name: 'Navigation' })
const current = () => within(pane()).getAllByRole('link').filter((link) => link.getAttribute('aria-current') === 'page')
const table = () => screen.getByRole('table', { name: 'Background Jobs' })
const nameCell = (label: string) => within(table()).getByRole('rowheader', { name: label })
const row = (label: string) => nameCell(label).closest('tr')!
/** A job's cells after its name: Last Run, Result, Next Run, Enabled, Schedule, and its actions */
const cells = (label: string) => within(row(label)).getAllByRole('cell').map((cell) => cell.textContent)
const actions = (label: string) => within(row(label)).queryAllByRole('button').map((button) => button.textContent)
const names = () =>
  within(table())
    .getAllByRole('row')
    .filter((one) => one.hasAttribute('data-job'))
    .map((one) => within(one).getByRole('rowheader').textContent)
const reads = (api: { callsTo: (pattern: string) => unknown[] }) => api.callsTo('GET /v1/admin/jobs').length
/** A moment for what was asked to be answered and drawn, on a real timeout, as the polls' intervals may be faked */
const settle = () => act(() => new Promise((resolve) => setTimeout(resolve, 20)))
const waitSeconds = async (seconds: number) => {
  await act(async () => void vi.advanceTimersByTime(seconds * 1000))
  await settle()
}

/**
 * axe over what's drawn, with the page asking for the light theme and then the dark one (`prefers-color-scheme`). jsdom
 * draws no colours, so this checks the markup under each; the colours themselves are tokens, which colours.test.ts checks.
 */
async function expectNoAxeViolationsInBothThemes(container: Element) {
  for (const dark of [false, true]) {
    window.matchMedia = (query: string) =>
      ({ matches: dark && query.includes('prefers-color-scheme: dark'), media: query, addEventListener: () => {}, removeEventListener: () => {}, addListener: () => {}, removeListener: () => {}, onchange: null, dispatchEvent: () => false }) as unknown as MediaQueryList
    await expectNoAxeViolations(container)
  }
}

async function openJobs(rows: A['Job'][] = jobRows, over: Routes = {}, options: JobRoutesOptions = {}) {
  const rendered = await renderApp('/jobs', { ...jobRoutes(rows, options), ...over })
  await within(await screen.findByRole('table', { name: 'Background Jobs' })).findAllByRole('rowheader')
  return rendered
}

describe('Background Jobs', () => {
  it('opens at /admin/jobs under its heading, the open screen in the pane', async () => {
    const { view } = await renderApp('/jobs', jobRoutes())
    expect(await screen.findByRole('heading', { level: 1, name: 'Background Jobs' })).toBeTruthy()
    expect(current()).toEqual([within(pane()).getByRole('link', { name: 'Background Jobs' })])
    await screen.findByRole('table', { name: 'Background Jobs' })
    await expectNoAxeViolations(view.container)
  })

  it("lists every job in one list, Healdan's then LogicalDOC's, each with its Last Run, Result, Next Run, Enabled and Schedule", async () => {
    const { view } = await openJobs()
    expect(within(table()).getAllByRole('columnheader').map((heading) => heading.textContent)).toEqual(['Job', 'Last Run', 'Result', 'Next Run', 'Enabled', 'Schedule', 'Actions'])
    expect(names()).toEqual(jobRows.map((job) => job.label))
    // Healdan's under their heading, then LogicalDOC's under theirs
    const groups = within(table()).getAllByRole('rowgroup').slice(1)
    expect(groups.map((group) => within(group).getAllByRole('rowheader')[0]?.textContent)).toEqual(["Healdan's Jobs", "LogicalDOC's Tasks"])
    expect(within(groups[0]!).getByRole('rowheader', { name: 'Storage Mover' })).toBeTruthy()
    expect(within(groups[1]!).getByRole('rowheader', { name: 'Indexer' })).toBeTruthy()

    // Times as the app's other lists show them, the full date and time
    expect(cells('Indexer').slice(0, 5)).toEqual([formatDateTime('2026-11-19T10:30:00Z'), 'Succeeded', formatDateTime('2026-11-19T11:00:00Z'), 'Yes', 'Every 30 minutes'])
    expect(within(row('Indexer')).getAllByRole('cell')[0]!.querySelector('time')?.getAttribute('datetime')).toBe('2026-11-19T10:30:00Z')
    // A cron expression as it is
    expect(cells('Index Optimizer')[4]).toBe('Cron: 0 30 2 * * ?')
    expect(cells('Digest Processor')[4]).toBe('Cron: 0 0 7 ? * MON-FRI')
    expect(cells('Statistics Collector')[4]).toBe('Every 24 hours')
    expect(cells('Paths Calculator')[4]).toBe('Every 5 minutes')
    // Done for good, never run since the start, and with no next run
    expect(cells('Email Index').slice(0, 5)).toEqual([formatDateTime('2026-11-13T08:00:00Z'), 'Done', 'None', 'Yes', ''])
    expect(cells('Storage Nightly Check').slice(0, 3)).toEqual(['Never', 'Not Run Yet', formatDateTime('2026-11-20T03:15:00Z')])
    // Disabled
    expect(cells('Tags Processor').slice(0, 5)).toEqual(['Never', 'Not Run Yet', 'None', 'No', 'Every hour'])
    await expectNoAxeViolationsInBothThemes(view.container)
  })

  it('says an overdue job is overdue', async () => {
    await openJobs()
    expect(cells('Statistics Collector')[2]).toBe(`${formatDateTime('2026-11-16T04:00:00Z')} Overdue`)
    expect(cells('Indexer')[2]).not.toContain('Overdue')
  })

  it('says a running job is running, whatever its last run did', async () => {
    await openJobs([jobOf({ running: true, canRun: false, canStop: true, result: null, lastEnd: null }), jobOfHealdan('storage-mover', 'Storage Mover', { running: true })])
    expect(cells('Indexer')[1]).toBe('Running')
    expect(cells('Storage Mover')[1]).toBe('Running')
  })

  it("shows a failed run's error under it, reachable as text: what the server logged, never markup", async () => {
    const hostile = '<img src=x onerror="alert(1)"><b>bold</b>'
    const { view } = await openJobs([...jobRows.slice(0, 4), jobOfHealdan('storage-retest', 'Storage Retests', { result: 'failed', error: `Wasabi EU said ${hostile}` }), jobOf()])
    expect(cells('Storage Retests')[1]).toBe('Failed')
    const under = row('Storage Retests').nextElementSibling as HTMLElement
    expect(under.hasAttribute('data-job')).toBe(false)
    expect(under.textContent).toBe(`Last Error${`Wasabi EU said ${hostile}`}`)
    // Named by its job for a screen reader that reads the cell
    const cell = within(under).getByRole('cell')
    expect(cell.getAttribute('headers')).toBe(nameCell('Storage Retests').id)
    expect(view.container.querySelector('img')).toBeNull()
    expect(view.container.querySelector('b')).toBeNull()
    // A job whose last run didn't fail has nothing under it
    expect((row('Indexer').nextElementSibling as HTMLElement | null)?.hasAttribute('data-job') ?? true).toBe(true)
    await expectNoAxeViolationsInBothThemes(view.container)
  })

  it('says a job switched off on this server is, and offers it nothing', async () => {
    const off = (name: string, label: string) => jobOfHealdan(name, label, { enabled: false, canRun: false, result: null, lastStart: null, lastEnd: null, nextRun: null })
    await openJobs([off('storage-mover', 'Storage Mover'), off('storage-retest', 'Storage Retests'), jobOf()])
    expect(cells('Storage Mover').slice(0, 5)).toEqual(['Never', 'Not Run Yet', 'None', 'Off on This Server', ''])
    expect(actions('Storage Mover')).toEqual([])
    expect(actions('Storage Retests')).toEqual([])
  })

  it('says why a first read failed, with Try Again', async () => {
    let failing = true
    await renderApp('/jobs', { ...jobRoutes(), 'GET /v1/admin/jobs': () => (failing ? problem(500, 'server-error') : jobRows) })
    expect(await screen.findByText('Something went wrong.')).toBeTruthy()
    expect(screen.queryByRole('table', { name: 'Background Jobs' })).toBeNull()
    failing = false
    await userEvent.click(screen.getByRole('button', { name: 'Try Again' }))
    expect(await within(await screen.findByRole('table', { name: 'Background Jobs' })).findByRole('rowheader', { name: 'Indexer' })).toBeTruthy()
  })

  it('says the server lists none', async () => {
    await renderApp('/jobs', { 'GET /v1/admin/jobs': [] })
    expect(await screen.findByText('The server lists no background jobs.')).toBeTruthy()
  })
})

describe('What each job allows', () => {
  it('offers Run Now, Stop, Enable and Disable only where the job allows them now, and Schedule… for the tasks with a schedule', async () => {
    const { view } = await openJobs([
      jobOfHealdan('preview', 'Previews'),
      jobOfHealdan('trash-destroyer', 'Trash Destroyer', { canRun: true }),
      jobOfHealdan('storage-mover', 'Storage Mover', { running: true }),
      jobOf(),
      jobOf({ name: 'TagsProcessor', label: 'Tags Processor', enabled: false, canRun: false }),
      jobOf({ name: 'PathCalculator', label: 'Paths Calculator', running: true, canRun: false, canStop: true, result: null, lastEnd: null }),
      // Asked to stop, and still running until its next check
      jobOf({ name: 'StatsCollector', label: 'Statistics Collector', running: true, canRun: false, canStop: false, result: null, lastEnd: null }),
    ])
    expect(actions('Previews')).toEqual([])
    expect(actions('Trash Destroyer')).toEqual(['Run Now'])
    expect(actions('Storage Mover')).toEqual([])
    expect(actions('Indexer')).toEqual(['Run Now', 'Disable', 'Schedule…'])
    expect(actions('Tags Processor')).toEqual(['Enable', 'Schedule…'])
    expect(actions('Paths Calculator')).toEqual(['Stop', 'Disable', 'Schedule…'])
    expect(actions('Statistics Collector')).toEqual(['Disable', 'Schedule…'])
    // Each named with its job, as there's one in each row
    expect(within(row('Indexer')).getAllByRole('button').map((button) => button.getAttribute('aria-label'))).toEqual(['Run Now: Indexer', 'Disable Indexer', 'Schedule of Indexer'])
    expect(within(row('Paths Calculator')).getByRole('button', { name: 'Stop Paths Calculator' })).toBeTruthy()
    expect(within(row('Tags Processor')).getByRole('button', { name: 'Enable Tags Processor' })).toBeTruthy()
    await expectNoAxeViolationsInBothThemes(view.container)
  })

  it('lets the keyboard reach every action, row by row', async () => {
    await openJobs([jobOfHealdan('trash-destroyer', 'Trash Destroyer', { canRun: true }), jobOf()])
    within(row('Trash Destroyer')).getByRole('button', { name: 'Run Now: Trash Destroyer' }).focus()
    const reached = [document.activeElement?.getAttribute('aria-label')]
    for (let i = 0; i < 3; i++) {
      await userEvent.tab()
      reached.push(document.activeElement?.getAttribute('aria-label'))
    }
    expect(reached).toEqual(['Run Now: Trash Destroyer', 'Run Now: Indexer', 'Disable Indexer', 'Schedule of Indexer'])
  })
})

describe('Run Now', () => {
  it('runs the job at once, without asking, holding its button while on its way; the list follows the answer, and the focus moves to Stop as it takes Run Now\'s place', async () => {
    const slow = slowAnswer<A['Job']>()
    const { api } = await openJobs(jobRows, { 'POST /v1/admin/jobs/*/run': () => slow.answered })
    const dashboardReads = api.callsTo('GET /v1/admin/dashboard').length
    const run = within(row('Indexer')).getByRole('button', { name: 'Run Now: Indexer' })
    run.focus()
    await userEvent.keyboard('{Enter}')
    await waitFor(() => expect(api.callsTo('POST /v1/admin/jobs/IndexerTask/run')).toHaveLength(1))
    expect(screen.queryByRole('alertdialog', { name: /Indexer\?$/ })).toBeNull()
    expect(run.getAttribute('data-pending')).toBe('true')
    await userEvent.keyboard('{Enter}')
    expect(api.callsTo('POST /v1/admin/jobs/IndexerTask/run')).toHaveLength(1)

    await act(async () => slow.answer(jobOf({ running: true, canRun: false, canStop: true, lastStart: '2026-11-19T10:42:00Z', lastEnd: null, result: null })))
    await waitFor(() => expect(cells('Indexer')[1]).toBe('Running'))
    expect(cells('Indexer')[0]).toBe(formatDateTime('2026-11-19T10:42:00Z'))
    // Run Now has gone, and Stop, in its place, has the focus
    expect(run.isConnected).toBe(false)
    expect(actions('Indexer')).toEqual(['Stop', 'Disable', 'Schedule…'])
    await waitFor(() => expect(document.activeElement).toBe(within(row('Indexer')).getByRole('button', { name: 'Stop Indexer' })))
    expect(await screen.findByText('Asked Indexer to run now')).toBeTruthy()
    // The left pane's count comes with the Dashboard's answer, asked again (P5-R20)
    await waitFor(() => expect(api.callsTo('GET /v1/admin/dashboard').length).toBeGreaterThan(dashboardReads))
  })

  it("gives the focus to the job's name once Run Now goes and nothing takes its place, as for one of Healdan's jobs", async () => {
    await openJobs()
    const run = within(row('Trash Destroyer')).getByRole('button', { name: 'Run Now: Trash Destroyer' })
    await userEvent.click(run)
    await waitFor(() => expect(cells('Trash Destroyer')[1]).toBe('Running'))
    expect(actions('Trash Destroyer')).toEqual([])
    await waitFor(() => expect(document.activeElement).toBe(nameCell('Trash Destroyer')))
  })

  it("says a refusal in the rule's words, in the job's row, and leaves the job as it was", async () => {
    await openJobs(jobRows, { 'POST /v1/admin/jobs/*/run': problem(409, 'refused', 'Trash Destroyer is already running.') })
    await userEvent.click(within(row('Trash Destroyer')).getByRole('button', { name: 'Run Now: Trash Destroyer' }))
    const said = await screen.findByRole('alert')
    expect(said.textContent).toBe('Trash Destroyer is already running.')
    expect(row('Trash Destroyer').nextElementSibling?.contains(said)).toBe(true)
    expect(cells('Trash Destroyer')[1]).toBe('Succeeded')
    expect(actions('Trash Destroyer')).toEqual(['Run Now'])
  })

  it('takes the refusal away once a new read shows the job changed since, and keeps it while it hasn\'t', async () => {
    let ran = false
    const routes = jobRoutes()
    const list = routes['GET /v1/admin/jobs'] as () => A['Job'][]
    await openJobs(jobRows, {
      'POST /v1/admin/jobs/*/run': problem(409, 'refused', 'Trash Destroyer is already running.'),
      'GET /v1/admin/jobs': () => list().map((job) => (ran && job.name === 'trash-destroyer' ? { ...job, lastStart: '2026-11-19T10:42:00Z', lastEnd: '2026-11-19T10:42:04Z' } : job)),
    })
    await userEvent.click(within(row('Trash Destroyer')).getByRole('button', { name: 'Run Now: Trash Destroyer' }))
    expect((await screen.findByRole('alert')).textContent).toBe('Trash Destroyer is already running.')
    // A read that finds the job as it was keeps the refusal
    await act(async () => void window.dispatchEvent(new Event('focus')))
    await settle()
    expect(screen.getByRole('alert').textContent).toBe('Trash Destroyer is already running.')
    // The run it was refused against has ended since, and Run Now is offered again
    ran = true
    await act(async () => void window.dispatchEvent(new Event('focus')))
    await waitFor(() => expect(cells('Trash Destroyer')[0]).toBe(formatDateTime('2026-11-19T10:42:00Z')))
    expect(screen.queryByRole('alert')).toBeNull()
    expect(row('Trash Destroyer').nextElementSibling?.hasAttribute('data-job') ?? true).toBe(true)
  })

  it("asks again shortly after, as the answer may not show the run yet, then every 30 seconds while it runs, and stops once it's done", async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
    let ended = false
    const routes = jobRoutes(jobRows, { runAnswersBefore: true })
    const list = routes['GET /v1/admin/jobs'] as () => A['Job'][]
    const { api } = await openJobs(jobRows, {
      ...routes,
      'GET /v1/admin/jobs': () => list().map((job) => (ended && job.name === 'trash-destroyer' ? { ...job, running: false, canRun: true, lastEnd: '2026-11-19T10:42:03Z' } : job)),
    })
    await settle()
    const before = reads(api)
    // Nothing runs, so nothing is asked on a timer
    await waitSeconds(60)
    expect(reads(api)).toBe(before)

    await userEvent.click(within(row('Trash Destroyer')).getByRole('button', { name: 'Run Now: Trash Destroyer' }))
    await settle()
    expect(api.callsTo('POST /v1/admin/jobs/trash-destroyer/run')).toHaveLength(1)
    // The answer is the job as it was
    expect(cells('Trash Destroyer')[1]).toBe('Succeeded')
    await waitSeconds(SOON_MS / 1000)
    expect(reads(api)).toBe(before + 1)
    expect(cells('Trash Destroyer')[1]).toBe('Running')
    await waitSeconds(30)
    expect(reads(api)).toBe(before + 2)
    ended = true
    await waitSeconds(30)
    expect(reads(api)).toBe(before + 3)
    expect(cells('Trash Destroyer')[1]).toBe('Succeeded')
    // Nothing runs now
    await waitSeconds(120)
    expect(reads(api)).toBe(before + 3)
  })

  it('asks again shortly after while another job runs, as the answer may not show the run yet', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
    const rows = [jobOfHealdan('storage-mover', 'Storage Mover', { running: true }), jobOfHealdan('trash-destroyer', 'Trash Destroyer', { canRun: true })]
    const { api } = await openJobs(rows, {}, { runAnswersBefore: true })
    await settle()
    const before = reads(api)
    await userEvent.click(within(row('Trash Destroyer')).getByRole('button', { name: 'Run Now: Trash Destroyer' }))
    await settle()
    expect(cells('Trash Destroyer')[1]).toBe('Succeeded')
    await waitSeconds(SOON_MS / 1000)
    expect(reads(api)).toBe(before + 1)
    expect(cells('Trash Destroyer')[1]).toBe('Running')
    // Then every 30 seconds, as jobs run
    await waitSeconds(29)
    expect(reads(api)).toBe(before + 1)
    await waitSeconds(1)
    expect(reads(api)).toBe(before + 2)
  })

  it('asks again shortly after even when another change comes within the wait, or takes the place of a read on its way', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
    const slowRead = slowAnswer<A['Job'][]>()
    let slow = false
    const routes = jobRoutes(jobRows, { runAnswersBefore: true })
    const list = routes['GET /v1/admin/jobs'] as () => A['Job'][]
    const { api } = await openJobs(jobRows, { ...routes, 'GET /v1/admin/jobs': () => (slow ? slowRead.answered : list()) })
    await settle()
    const before = reads(api)
    await userEvent.click(within(row('Trash Destroyer')).getByRole('button', { name: 'Run Now: Trash Destroyer' }))
    await settle()
    // Another change within the two seconds, whose answer is newer than the Run Now's
    await userEvent.click(within(row('Tags Processor')).getByRole('button', { name: 'Enable Tags Processor' }))
    await waitFor(() => expect(cells('Tags Processor')[3]).toBe('Yes'))
    await waitSeconds(SOON_MS / 1000)
    expect(reads(api)).toBe(before + 1)
    expect(cells('Trash Destroyer')[1]).toBe('Running')

    // A Run Now whose re-read is on its way when another change comes: that change cancels the read, and it's asked again
    await userEvent.click(within(row('Storage Purge')).getByRole('button', { name: 'Run Now: Storage Purge' }))
    await settle()
    slow = true
    await waitSeconds(SOON_MS / 1000)
    expect(reads(api)).toBe(before + 2)
    slow = false
    await userEvent.click(within(row('Tags Processor')).getByRole('button', { name: 'Disable Tags Processor' }))
    await userEvent.click(within(await screen.findByRole('alertdialog', { name: 'Disable Tags Processor?' })).getByRole('button', { name: 'Disable' }))
    await waitFor(() => expect(screen.queryByRole('alertdialog', { name: 'Disable Tags Processor?' })).toBeNull())
    await waitFor(() => expect(cells('Tags Processor')[3]).toBe('No'))
    // The cancelled read's answer comes after all, and counts for nothing, even once a further change is put in the list
    await act(async () => slowRead.answer(list().map((job) => (job.name === 'storage-purge' ? { ...job, running: false } : job))))
    await settle()
    await userEvent.click(within(row('Tags Processor')).getByRole('button', { name: 'Enable Tags Processor' }))
    await waitFor(() => expect(cells('Tags Processor')[3]).toBe('Yes'))
    expect(reads(api)).toBe(before + 2)
    await waitSeconds(SOON_MS / 1000)
    expect(reads(api)).toBe(before + 3)
    expect(cells('Storage Purge')[1]).toBe('Running')
  })
})

describe('Stop and Disable', () => {
  const running = () => [jobOf({ running: true, canRun: false, canStop: true, result: null, lastEnd: null }), jobOf({ name: 'TagsProcessor', label: 'Tags Processor' })]

  it('Stop asks first, naming the job, and Cancel sends nothing', async () => {
    const { api, view } = await openJobs(running())
    const stop = within(row('Indexer')).getByRole('button', { name: 'Stop Indexer' })
    await userEvent.click(stop)
    const question = await screen.findByRole('alertdialog', { name: 'Stop Indexer?' })
    expect(within(question).getByText(/It stops at its next check/)).toBeTruthy()
    await expectNoAxeViolationsInBothThemes(view.container.ownerDocument.body)
    await userEvent.click(within(question).getByRole('button', { name: 'Cancel' }))
    await waitFor(() => expect(screen.queryByRole('alertdialog', { name: 'Stop Indexer?' })).toBeNull())
    expect(api.callsTo('POST /v1/admin/jobs/*/stop')).toHaveLength(0)
    await waitFor(() => expect(document.activeElement).toBe(stop))
  })

  it("Stop, once asked, asks the run to stop; the job shows running until it does, and the focus goes to its name as Stop goes", async () => {
    const { api } = await openJobs(running())
    const dashboardReads = api.callsTo('GET /v1/admin/dashboard').length
    await userEvent.click(within(row('Indexer')).getByRole('button', { name: 'Stop Indexer' }))
    await userEvent.click(within(await screen.findByRole('alertdialog', { name: 'Stop Indexer?' })).getByRole('button', { name: 'Stop' }))
    await waitFor(() => expect(screen.queryByRole('alertdialog', { name: 'Stop Indexer?' })).toBeNull())
    expect(api.callsTo('POST /v1/admin/jobs/IndexerTask/stop')).toHaveLength(1)
    await waitFor(() => expect(actions('Indexer')).toEqual(['Disable', 'Schedule…']))
    expect(cells('Indexer')[1]).toBe('Running')
    await waitFor(() => expect(document.activeElement).toBe(nameCell('Indexer')))
    expect(await screen.findByText('Asked Indexer to stop')).toBeTruthy()
    await waitFor(() => expect(api.callsTo('GET /v1/admin/dashboard').length).toBeGreaterThan(dashboardReads))
  })

  it("asks again shortly after a Stop, though its answer shows the run going on, and nothing more once it has stopped", async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
    let stopped = false
    const routes = jobRoutes(running())
    const list = routes['GET /v1/admin/jobs'] as () => A['Job'][]
    const { api } = await openJobs([], {
      ...routes,
      'GET /v1/admin/jobs': () => list().map((job) => (stopped && job.name === 'IndexerTask' ? { ...job, running: false, canRun: true, result: 'succeeded' as const, lastEnd: '2026-11-19T10:42:05Z' } : job)),
    })
    await settle()
    const before = reads(api)
    await userEvent.click(within(row('Indexer')).getByRole('button', { name: 'Stop Indexer' }))
    await userEvent.click(within(await screen.findByRole('alertdialog', { name: 'Stop Indexer?' })).getByRole('button', { name: 'Stop' }))
    await waitFor(() => expect(screen.queryByRole('alertdialog', { name: 'Stop Indexer?' })).toBeNull())
    await settle()
    expect(cells('Indexer')[1]).toBe('Running')
    // The run stopped at its next check, a moment after
    stopped = true
    await waitSeconds(SOON_MS / 1000)
    expect(reads(api)).toBe(before + 1)
    expect(cells('Indexer')[1]).toBe('Succeeded')
    expect(actions('Indexer')).toEqual(['Run Now', 'Disable', 'Schedule…'])
    // Nothing runs now, so nothing more is asked on a timer
    await waitSeconds(120)
    expect(reads(api)).toBe(before + 1)
  })

  it('asks again shortly after a Stop, then every 30 seconds while the run goes on', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
    const { api } = await openJobs(running())
    await settle()
    const before = reads(api)
    await userEvent.click(within(row('Indexer')).getByRole('button', { name: 'Stop Indexer' }))
    await userEvent.click(within(await screen.findByRole('alertdialog', { name: 'Stop Indexer?' })).getByRole('button', { name: 'Stop' }))
    await waitFor(() => expect(screen.queryByRole('alertdialog', { name: 'Stop Indexer?' })).toBeNull())
    await settle()
    await waitSeconds(SOON_MS / 1000)
    expect(reads(api)).toBe(before + 1)
    await waitSeconds(29)
    expect(reads(api)).toBe(before + 1)
    await waitSeconds(1)
    expect(reads(api)).toBe(before + 2)
  })

  it('Disable asks first, naming the job, then disables it; the focus is on Enable, which takes its place', async () => {
    const { api } = await openJobs()
    const dashboardReads = api.callsTo('GET /v1/admin/dashboard').length
    const toggle = within(row('Indexer')).getByRole('button', { name: 'Disable Indexer' })
    await userEvent.click(toggle)
    const question = await screen.findByRole('alertdialog', { name: 'Disable Indexer?' })
    expect(within(question).getByText(/A run already going on goes on to its end/)).toBeTruthy()
    expect(api.callsTo('POST /v1/admin/jobs/*/disable')).toHaveLength(0)
    await userEvent.click(within(question).getByRole('button', { name: 'Disable' }))
    await waitFor(() => expect(screen.queryByRole('alertdialog', { name: 'Disable Indexer?' })).toBeNull())
    expect(api.callsTo('POST /v1/admin/jobs/IndexerTask/disable')).toHaveLength(1)
    await waitFor(() => expect(cells('Indexer')[3]).toBe('No'))
    expect(actions('Indexer')).toEqual(['Enable', 'Schedule…'])
    await waitFor(() => expect(document.activeElement).toBe(toggle))
    expect(toggle.getAttribute('aria-label')).toBe('Enable Indexer')
    expect(await screen.findByText('Disabled Indexer')).toBeTruthy()
    await waitFor(() => expect(api.callsTo('GET /v1/admin/dashboard').length).toBeGreaterThan(dashboardReads))
  })

  it("keeps a refusal in the question, in the rule's words, and the job as it was", async () => {
    await openJobs(jobRows, { 'POST /v1/admin/jobs/*/disable': problem(409, 'refused', "Indexer can't be disabled now.") })
    await userEvent.click(within(row('Indexer')).getByRole('button', { name: 'Disable Indexer' }))
    const question = await screen.findByRole('alertdialog', { name: 'Disable Indexer?' })
    await userEvent.click(within(question).getByRole('button', { name: 'Disable' }))
    expect((await within(question).findByRole('alert')).textContent).toBe("Indexer can't be disabled now.")
    await userEvent.click(within(question).getByRole('button', { name: 'Cancel' }))
    await waitFor(() => expect(screen.queryByRole('alertdialog', { name: 'Disable Indexer?' })).toBeNull())
    expect(cells('Indexer')[3]).toBe('Yes')
  })

  it('Enable enables at once, without asking, holding its button while on its way; the focus stays on it as it becomes Disable', async () => {
    const slow = slowAnswer<A['Job']>()
    const { api } = await openJobs(jobRows, { 'POST /v1/admin/jobs/*/enable': () => slow.answered })
    const dashboardReads = api.callsTo('GET /v1/admin/dashboard').length
    const toggle = within(row('Tags Processor')).getByRole('button', { name: 'Enable Tags Processor' })
    await userEvent.click(toggle)
    await waitFor(() => expect(api.callsTo('POST /v1/admin/jobs/TagsProcessor/enable')).toHaveLength(1))
    expect(screen.queryByRole('alertdialog')).toBeNull()
    expect(toggle.getAttribute('data-pending')).toBe('true')
    await userEvent.click(toggle)
    expect(api.callsTo('POST /v1/admin/jobs/TagsProcessor/enable')).toHaveLength(1)
    const tags = jobRows.find((job) => job.name === 'TagsProcessor')!
    await act(async () => slow.answer({ ...tags, enabled: true, canRun: true, nextRun: '2026-11-19T11:00:00Z' }))
    await waitFor(() => expect(cells('Tags Processor')[3]).toBe('Yes'))
    expect(actions('Tags Processor')).toEqual(['Run Now', 'Disable', 'Schedule…'])
    expect(document.activeElement).toBe(toggle)
    expect(toggle.getAttribute('aria-label')).toBe('Disable Tags Processor')
    expect(await screen.findByText('Enabled Tags Processor')).toBeTruthy()
    await waitFor(() => expect(api.callsTo('GET /v1/admin/dashboard').length).toBeGreaterThan(dashboardReads))
  })
})

describe('The focus, when a button goes', () => {
  it("goes to the job's name when a new read takes away the button it was on, as Stop once the run ends", async () => {
    let ended = false
    const routes = jobRoutes([jobOf({ running: true, canRun: false, canStop: true, result: null, lastEnd: null })])
    const list = routes['GET /v1/admin/jobs'] as () => A['Job'][]
    await openJobs([], { ...routes, 'GET /v1/admin/jobs': () => list().map((job) => (ended ? { ...job, running: false, canRun: true, canStop: false, result: 'succeeded' as const } : job)) })
    within(row('Indexer')).getByRole('button', { name: 'Stop Indexer' }).focus()
    ended = true
    await act(async () => void window.dispatchEvent(new Event('focus')))
    await waitFor(() => expect(actions('Indexer')).toEqual(['Run Now', 'Disable', 'Schedule…']))
    expect(document.activeElement).toBe(nameCell('Indexer'))
  })

  it('is left alone when it had already left the list', async () => {
    let ended = false
    const routes = jobRoutes([jobOf({ running: true, canRun: false, canStop: true, result: null, lastEnd: null })])
    const list = routes['GET /v1/admin/jobs'] as () => A['Job'][]
    await openJobs([], { ...routes, 'GET /v1/admin/jobs': () => list().map((job) => (ended ? { ...job, running: false, canRun: true, canStop: false, result: 'succeeded' as const } : job)) })
    const stop = within(row('Indexer')).getByRole('button', { name: 'Stop Indexer' })
    stop.focus()
    act(() => stop.blur())
    await settle()
    ended = true
    await act(async () => void window.dispatchEvent(new Event('focus')))
    await waitFor(() => expect(actions('Indexer')).toEqual(['Run Now', 'Disable', 'Schedule…']))
    await settle()
    expect(document.activeElement).toBe(document.body)
  })
})

describe('Schedule…', () => {
  const dialog = () => screen.getByRole('dialog', { name: 'Schedule of Indexer' })
  const every = () => within(dialog()).getByRole('textbox', { name: 'Every' }) as HTMLInputElement
  const unit = () => within(dialog()).getByRole('combobox', { name: 'Unit' }) as HTMLSelectElement
  const cron = () => within(dialog()).getByRole('textbox', { name: 'Cron Expression' }) as HTMLInputElement
  const save = () => within(dialog()).getByRole('button', { name: 'Save' })
  const openSchedule = async (label = 'Indexer') => {
    await userEvent.click(within(row(label)).getByRole('button', { name: `Schedule of ${label}` }))
    return screen.findByRole('dialog', { name: `Schedule of ${label}` })
  }

  it("opens on the task's schedule, every so many minutes, and Save waits for a change", async () => {
    const { view } = await openJobs()
    await openSchedule()
    expect((within(dialog()).getByRole('radio', { name: 'Every So Often' }) as HTMLInputElement).checked).toBe(true)
    expect(every().value).toBe('30')
    expect(unit().value).toBe('minutes')
    expect(within(unit()).getAllByRole('option').map((option) => option.textContent)).toEqual(['Seconds', 'Minutes', 'Hours'])
    expect(within(dialog()).queryByRole('textbox', { name: 'Cron Expression' })).toBeNull()
    expect((save() as HTMLButtonElement).disabled).toBe(true)
    expect(document.activeElement).toBe(every())
    await expectNoAxeViolationsInBothThemes(view.container.ownerDocument.body)
  })

  it('says at once why Save waits when the schedule it opens on is one the server would refuse now', async () => {
    await openJobs([jobOf({ schedule: { mode: 'simple', intervalSeconds: 40_000_000, cron: null } })])
    await openSchedule()
    expect(every().value).toBe('40000000')
    expect(unit().value).toBe('seconds')
    expect(within(dialog()).getByText('Type a whole number from 1 to 31,536,000.')).toBeTruthy()
    expect(every().getAttribute('aria-invalid')).toBe('true')
    expect((save() as HTMLButtonElement).disabled).toBe(true)
  })

  it('opens on a cron expression, as it is', async () => {
    const { view } = await openJobs()
    const opened = await openSchedule('Index Optimizer')
    expect((within(opened).getByRole('radio', { name: 'When a Cron Expression Says' }) as HTMLInputElement).checked).toBe(true)
    expect((within(opened).getByRole('textbox', { name: 'Cron Expression' }) as HTMLInputElement).value).toBe('0 30 2 * * ?')
    expect(within(opened).queryByRole('textbox', { name: 'Every' })).toBeNull()
    await expectNoAxeViolationsInBothThemes(view.container.ownerDocument.body)
  })

  it('saves a new interval in seconds, the cron member left out; the row follows, the Dashboard is asked again, and the dialog closes', async () => {
    const { api } = await openJobs()
    const dashboardReads = api.callsTo('GET /v1/admin/dashboard').length
    await openSchedule()
    await userEvent.clear(every())
    await userEvent.type(every(), '45')
    await userEvent.click(save())
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(api.callsTo('PUT /v1/admin/jobs/IndexerTask/schedule').map((call) => call.body)).toEqual([{ mode: 'simple', intervalSeconds: 2700 }])
    await waitFor(() => expect(cells('Indexer')[4]).toBe('Every 45 minutes'))
    expect(await screen.findByText('Saved the schedule of Indexer')).toBeTruthy()
    await waitFor(() => expect(api.callsTo('GET /v1/admin/dashboard').length).toBeGreaterThan(dashboardReads))
    await waitFor(() => expect(document.activeElement).toBe(within(row('Indexer')).getByRole('button', { name: 'Schedule of Indexer' })))
  })

  it('changes the unit alone, as a new interval', async () => {
    const { api } = await openJobs()
    await openSchedule()
    fireEvent.change(unit(), { target: { value: 'hours' } })
    await userEvent.click(save())
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(api.callsTo('PUT /v1/admin/jobs/IndexerTask/schedule').map((call) => call.body)).toEqual([{ mode: 'simple', intervalSeconds: 108_000 }])
    await waitFor(() => expect(cells('Indexer')[4]).toBe('Every 30 hours'))
  })

  it('saves a cron expression, trimmed, the interval left out', async () => {
    const { api } = await openJobs()
    await openSchedule()
    await userEvent.click(within(dialog()).getByRole('radio', { name: 'When a Cron Expression Says' }))
    expect(within(dialog()).queryByRole('textbox', { name: 'Every' })).toBeNull()
    // Nothing typed yet says nothing, and Save waits
    expect(within(dialog()).queryByText('Type a cron expression.')).toBeNull()
    expect((save() as HTMLButtonElement).disabled).toBe(true)
    await userEvent.type(cron(), '  0 0 3 * * ?  ')
    await userEvent.click(save())
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(api.callsTo('PUT /v1/admin/jobs/IndexerTask/schedule').map((call) => call.body)).toEqual([{ mode: 'cron', cron: '0 0 3 * * ?' }])
    await waitFor(() => expect(cells('Indexer')[4]).toBe('Cron: 0 0 3 * * ?'))
  })

  it("checks what the server checks, before Save: a whole number from 1 to a year's seconds in the unit picked, and a cron expression that isn't blank", async () => {
    const { api } = await openJobs()
    await openSchedule()
    const said = () => within(dialog()).queryByText(/^Type a whole number from 1 to/)?.textContent
    for (const [typed, picked, wrong] of [
      ['0', 'minutes', 'Type a whole number from 1 to 525,600.'],
      ['525600', 'minutes', undefined],
      ['525601', 'minutes', 'Type a whole number from 1 to 525,600.'],
      ['8760', 'hours', undefined],
      ['8761', 'hours', 'Type a whole number from 1 to 8,760.'],
      ['31536000', 'seconds', undefined],
      ['31536001', 'seconds', 'Type a whole number from 1 to 31,536,000.'],
      ['1.5', 'hours', 'Type a whole number from 1 to 8,760.'],
      ['', 'minutes', 'Type a whole number from 1 to 525,600.'],
    ] as const) {
      fireEvent.change(unit(), { target: { value: picked } })
      fireEvent.change(every(), { target: { value: typed } })
      expect([typed, picked, said()]).toEqual([typed, picked, wrong])
      expect([typed, picked, (save() as HTMLButtonElement).disabled]).toEqual([typed, picked, wrong !== undefined])
      expect([typed, picked, every().getAttribute('aria-invalid')]).toEqual([typed, picked, wrong === undefined ? null : 'true'])
    }
    await userEvent.click(within(dialog()).getByRole('radio', { name: 'When a Cron Expression Says' }))
    await userEvent.type(cron(), '   ')
    expect(within(dialog()).getByText('Type a cron expression.')).toBeTruthy()
    expect((save() as HTMLButtonElement).disabled).toBe(true)
    expect(api.callsTo('PUT /v1/admin/jobs/*/schedule')).toHaveLength(0)
  })

  it('says beside the field what the server refused of it, until it is changed', async () => {
    const { api } = await openJobs()
    await openSchedule()
    await userEvent.click(within(dialog()).getByRole('radio', { name: 'When a Cron Expression Says' }))
    await userEvent.type(cron(), '0 0 3 * *')
    await userEvent.click(save())
    expect(await within(dialog()).findByText("The server doesn't take that expression: it isn't in Quartz's form, or it never comes round again.")).toBeTruthy()
    expect(cron().getAttribute('aria-invalid')).toBe('true')
    // Said beside the field, not again above the buttons
    expect(within(dialog()).queryByRole('alert')).toBeNull()
    expect(api.callsTo('PUT /v1/admin/jobs/IndexerTask/schedule')).toHaveLength(1)
    await userEvent.type(cron(), ' ?')
    expect(within(dialog()).queryByText(/The server doesn't take that expression/)).toBeNull()
    await userEvent.click(save())
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    await waitFor(() => expect(cells('Indexer')[4]).toBe('Cron: 0 0 3 * * ?'))
  })

  it("says the server's refusal of the interval beside Every", async () => {
    await openJobs(jobRows, { 'PUT /v1/admin/jobs/*/schedule': jobFieldProblem('intervalSeconds', 'out-of-range') })
    await openSchedule()
    fireEvent.change(every(), { target: { value: '40' } })
    await userEvent.click(save())
    expect(await within(dialog()).findByText('Type a whole number from 1 to 525,600.')).toBeTruthy()
    expect(every().getAttribute('aria-invalid')).toBe('true')
  })

  it("says what names no field it shows, and a 409 refused, in the rule's words above the buttons", async () => {
    let answer: unknown = jobFieldProblem('mode', 'invalid')
    await openJobs(jobRows, { 'PUT /v1/admin/jobs/*/schedule': () => answer })
    await openSchedule()
    fireEvent.change(every(), { target: { value: '40' } })
    await userEvent.click(save())
    expect((await within(dialog()).findByRole('alert')).textContent).toBe('Some details need fixing.')
    answer = problem(409, 'refused', 'Indexer has no schedule here.')
    fireEvent.change(every(), { target: { value: '41' } })
    await userEvent.click(save())
    expect((await within(dialog()).findByRole('alert')).textContent).toBe('Indexer has no schedule here.')
    await userEvent.click(within(dialog()).getByRole('button', { name: 'Cancel' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(cells('Indexer')[4]).toBe('Every 30 minutes')
  })

  it('holds while saving: the fields, Cancel, Esc and a click outside do nothing until the answer', async () => {
    const slow = slowAnswer<A['Job']>()
    const { api } = await openJobs(jobRows, { 'PUT /v1/admin/jobs/*/schedule': () => slow.answered })
    await openSchedule()
    fireEvent.change(every(), { target: { value: '40' } })
    await userEvent.click(save())
    await waitFor(() => expect(api.callsTo('PUT /v1/admin/jobs/IndexerTask/schedule')).toHaveLength(1))
    expect(save().getAttribute('data-pending')).toBe('true')
    expect((within(dialog()).getByRole('button', { name: 'Cancel' }) as HTMLButtonElement).disabled).toBe(true)
    expect(every().closest('fieldset')?.disabled).toBe(true)
    await userEvent.keyboard('{Escape}')
    expect(screen.getByRole('dialog', { name: 'Schedule of Indexer' })).toBeTruthy()
    await act(async () => slow.answer(jobOf({ schedule: { mode: 'simple', intervalSeconds: 2400, cron: null } })))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    await waitFor(() => expect(cells('Indexer')[4]).toBe('Every 40 minutes'))
  })

  it("isn't offered for Healdan's jobs, which have no schedule here", async () => {
    await openJobs()
    expect(within(row('Trash Destroyer')).queryByRole('button', { name: /^Schedule/ })).toBeNull()
    expect(cells('Trash Destroyer')[4]).toBe('')
  })
})

describe('Asking again', () => {
  const runningRows = () => [jobOfHealdan('storage-mover', 'Storage Mover', { running: true }), jobOf()]

  async function polling(rows: A['Job'][]) {
    // Only the polls' intervals are moved on by hand; the app's other waits run as they do
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
    const rendered = await openJobs(rows)
    await settle()
    expect(reads(rendered.api)).toBe(1)
    return rendered
  }

  it('asks again every 30 seconds while a job is running and the page is visible', async () => {
    const { api } = await polling(runningRows())
    await waitSeconds(29)
    expect(reads(api)).toBe(1)
    await waitSeconds(1)
    expect(reads(api)).toBe(2)
    await waitSeconds(30)
    expect(reads(api)).toBe(3)
  })

  it('asks nothing on a timer while nothing runs', async () => {
    const { api } = await polling(jobRows)
    await waitSeconds(120)
    expect(reads(api)).toBe(1)
  })

  it('asks nothing while the page is hidden, and asks again when the person comes back', async () => {
    const { api } = await polling(runningRows())
    hidePage()
    await waitSeconds(120)
    expect(reads(api)).toBe(1)
    returnToPage()
    await settle()
    expect(reads(api)).toBe(2)
    await waitSeconds(30)
    expect(reads(api)).toBe(3)
  })

  it('asks again when the person comes back to the window, however fresh the answer', async () => {
    const { api } = await polling(jobRows)
    await act(async () => void window.dispatchEvent(new Event('focus')))
    await settle()
    expect(reads(api)).toBe(2)
  })

  it('stops when the page is left', async () => {
    const { api, router } = await polling(runningRows())
    act(() => router.history.push('/admin/no-such-page'))
    await screen.findByRole('heading', { level: 1, name: 'Page Not Found' })
    await waitSeconds(120)
    expect(reads(api)).toBe(1)
  })

  it("keeps the list while a new read fails, saying it couldn't refresh, with Try Again (P4-R15)", async () => {
    let failing = false
    const routes = jobRoutes()
    const list = routes['GET /v1/admin/jobs'] as () => A['Job'][]
    await openJobs(jobRows, { 'GET /v1/admin/jobs': () => (failing ? problem(500, 'server-error') : list()) })
    failing = true
    await act(async () => void window.dispatchEvent(new Event('focus')))
    expect(await screen.findByText("Couldn't refresh this, so it shows what was read last.")).toBeTruthy()
    expect(names()).toEqual(jobRows.map((job) => job.label))
    failing = false
    await userEvent.click(screen.getByRole('button', { name: 'Try Again' }))
    await waitFor(() => expect(screen.queryByText("Couldn't refresh this, so it shows what was read last.")).toBeNull())
    expect(names()).toEqual(jobRows.map((job) => job.label))
  })
})
