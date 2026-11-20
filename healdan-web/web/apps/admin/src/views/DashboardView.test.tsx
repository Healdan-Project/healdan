// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { formatDate, setLabels } from '@healdan/ui'
import { expectNoAxeViolations } from '../test/axe'
import { attention, dashboard, health, problem, tile, usage, usageNotYet } from '../test/fakeApi'
import { renderApp } from '../test/renderApp'
import { hidePage, returnToPage } from '../test/visibility'

const region = (name: string) => screen.findByRole('region', { name })
/** A row's or tile's part, by the data attribute that marks it: its message, detail or status */
const marked = (element: HTMLElement, name: string) => element.querySelector<HTMLElement>(`[data-${name}]`)!
const main = () => screen.getByRole('main')

describe('The Dashboard', () => {
  it('opens at /admin/, its parts in order: Needs Attention first, then Server Health and Usage', async () => {
    const { view } = await renderApp('/')
    expect(screen.getByRole('heading', { level: 1, name: 'Dashboard' })).toBeTruthy()
    await region('Needs Attention')
    const parts = within(main()).getAllByRole('heading', { level: 2 }).map((heading) => heading.textContent)
    expect(parts).toEqual(['Needs Attention', 'Server Health', 'Usage'])
    await screen.findByRole('img', { name: 'Documents Filed, Last 30 Days' })
    await expectNoAxeViolations(view.container)
  })

  it('says when the server read its oldest figure, and Refresh asks for every part again', async () => {
    const { api } = await renderApp('/')
    await region('Needs Attention')
    // The server's readAt, not when the page asked
    await waitFor(() => expect(screen.getByText(`Updated ${formatDate(dashboard.readAt)}`)).toBeTruthy())
    expect(api.callsTo('GET /v1/admin/dashboard')).toHaveLength(1)
    expect(api.callsTo('GET /v1/admin/dashboard/usage')).toHaveLength(1)
    await userEvent.click(screen.getByRole('button', { name: 'Refresh' }))
    await waitFor(() => expect(api.callsTo('GET /v1/admin/dashboard')).toHaveLength(2))
    expect(api.callsTo('GET /v1/admin/dashboard/usage')).toHaveLength(2)
  })
})

describe('Needs Attention', () => {
  it('lists what to act on, in the server\'s order, each saying how many and more, and linking to where it is fixed', async () => {
    await renderApp('/')
    const box = await region('Needs Attention')
    const items = await within(box).findAllByRole('listitem')
    expect(items.map((item) => marked(item, 'message').textContent)).toEqual([
      '3 files are stuck moving to storage',
      "2 documents couldn't be indexed, so search won't find their text",
      '14 unresolved errors in the last 24 hours',
    ])
    expect(items.map((item) => marked(item, 'detail').textContent)).toEqual(attention.map((item) => item.detail))
    // Each row's link is the server's: the Classic Interface until its screen ships, said as such
    const links = items.map((item) => within(item).getByRole('link'))
    expect(links.map((link) => link.getAttribute('href'))).toEqual(['/frontend.jsp', '/admin/search-index', '/frontend.jsp'])
    expect(links.map((link) => link.textContent)).toEqual(['Classic Interface', 'Open', 'Classic Interface'])
    // And the link says what it's for, from its row
    expect(links[0]!.getAttribute('aria-describedby')).toBe(marked(items[0]!, 'message').id)
  })

  it('says so in one line when nothing needs attention', async () => {
    await renderApp('/', { 'GET /v1/admin/dashboard': { ...dashboard, attention: [] } })
    const box = await region('Needs Attention')
    expect(await within(box).findByText('Nothing needs your attention.')).toBeTruthy()
    expect(within(box).queryAllByRole('listitem')).toEqual([])
  })

  it('says one thing in the singular', async () => {
    await renderApp('/', { 'GET /v1/admin/dashboard': { ...dashboard, attention: [{ code: 'disk-low', count: 1, detail: '/opt/logicaldoc/repository, 91% full', link: '/frontend.jsp' }] } })
    const box = await region('Needs Attention')
    expect(await within(box).findByText('1 disk is more than 85% full')).toBeTruthy()
  })

  it('says what it couldn\'t check, and never that nothing needs attention while something is unchecked', async () => {
    const { view } = await renderApp('/', { 'GET /v1/admin/dashboard': { ...dashboard, attention: [], unchecked: ['files-stuck', 'errors'] } })
    const box = await region('Needs Attention')
    expect(await within(box).findByText("Couldn't check Stuck Files, Errors.")).toBeTruthy()
    expect(within(box).queryByText('Nothing needs your attention.')).toBeNull()
    await expectNoAxeViolations(view.container)
  })

  it('says what it couldn\'t check beside what it found', async () => {
    await renderApp('/', { 'GET /v1/admin/dashboard': { ...dashboard, unchecked: ['disk-low'] } })
    const box = await region('Needs Attention')
    await within(box).findByText('3 files are stuck moving to storage')
    expect(within(box).getByText("Couldn't check Disk Space.")).toBeTruthy()
  })

  it("shows its own error when the server can't answer, never the server's own words, and Try Again asks again", async () => {
    let failing = true
    const { api } = await renderApp('/', { 'GET /v1/admin/dashboard': () => (failing ? problem(500, 'server-error') : dashboard) })
    const box = await region('Needs Attention')
    expect(await within(box).findByText('Something went wrong.')).toBeTruthy()
    expect(screen.queryByText('A server sentence that must not be shown raw.')).toBeNull()
    failing = false
    await userEvent.click(within(box).getByRole('button', { name: 'Try Again' }))
    expect(await within(box).findByText('3 files are stuck moving to storage')).toBeTruthy()
    expect(api.callsTo('GET /v1/admin/dashboard')).toHaveLength(2)
  })
})

describe('Server Health', () => {
  it('has the ten tiles in the server\'s order, each with its status in words, opening the screen behind it', async () => {
    const { view } = await renderApp('/')
    const section = await region('Server Health')
    const tiles = await within(section).findAllByRole('listitem')
    expect(tiles.map((one) => within(one).getByRole('heading', { level: 3 }).textContent)).toEqual([
      'Server',
      'Database',
      'Disk',
      'Memory',
      'Sign-In',
      'Storage Backends',
      'Search Index',
      'Previews',
      'Background Jobs',
      'Outgoing Email',
    ])
    expect(tiles.map((one) => marked(one, 'status').textContent)).toEqual([
      'OK',
      'OK',
      'OK',
      'OK',
      'OK',
      'Needs Attention',
      'Needs Attention',
      'OK',
      'OK',
      'Off',
    ])
    expect(tiles.map((one) => marked(one, 'detail').textContent)).toEqual(health.map((one) => one.detail))
    expect(tiles.map((one) => within(one).getByRole('link').getAttribute('href'))).toEqual(health.map((one) => one.link))
    // The tile's name is its link, which covers the tile
    expect(within(tiles[0]!).getByRole('link', { name: 'Server' })).toBeTruthy()
    await expectNoAxeViolations(view.container)
  })

  it("says a status this app doesn't know yet, a newer server's, as the server names it", async () => {
    const newer = { ...tile('server'), status: 'degraded' } as unknown as (typeof health)[number]
    await renderApp('/', { 'GET /v1/admin/dashboard': { ...dashboard, health: [newer, ...health.slice(1)] } })
    const tiles = await within(await region('Server Health')).findAllByRole('listitem')
    expect(marked(tiles[0]!, 'status').textContent).toBe('degraded')
  })

  it('shows how full the disk and the memory are, as a bar beside the words that say it', async () => {
    await renderApp('/')
    const section = await region('Server Health')
    const tiles = await within(section).findAllByRole('listitem')
    const fill = (index: number) => tiles[index]!.querySelector<HTMLElement>('[data-fill]')
    expect(fill(2)?.style.width).toBe('39%')
    expect(fill(3)?.style.width).toBe('60%')
    expect(fill(2)?.parentElement?.getAttribute('aria-hidden')).toBe('true')
    // The database has a size and no total, so no bar
    expect(fill(1)).toBeNull()
  })

  it("shows a tile's own problem when its part couldn't be read, and the other nine whole", async () => {
    const broken = health.map((one) => (one.name === 'storage-backends' ? tile('storage-backends', { status: 'attention', detail: null, problem: "The storage port didn't answer." }) : one))
    await renderApp('/', { 'GET /v1/admin/dashboard': { ...dashboard, health: broken } })
    const section = await region('Server Health')
    const tiles = await within(section).findAllByRole('listitem')
    expect(tiles).toHaveLength(10)
    const storage = tiles[5]!
    expect(marked(storage, 'status').textContent).toBe('Unavailable')
    expect(marked(storage, 'detail').textContent).toBe("The storage port didn't answer.")
    expect(marked(tiles[0]!, 'detail').textContent).toBe('Up 6 days, Healdan 9.3.2')
    expect(marked(tiles[9]!, 'status').textContent).toBe('Off')
  })

  it('shows the same error as Needs Attention when the server can\'t answer, as both come from one request', async () => {
    await renderApp('/', { 'GET /v1/admin/dashboard': problem(500, 'server-error') })
    const part = await region('Server Health')
    expect(await within(part).findByText('Something went wrong.')).toBeTruthy()
    expect(within(part).getByRole('button', { name: 'Try Again' })).toBeTruthy()
  })
})

describe('Usage', () => {
  it('has the totals, each with its recent change, and who is signed in now', async () => {
    await renderApp('/')
    const part = await region('Usage')
    const totals = await within(part).findByRole('list', { name: 'Totals' })
    expect(within(totals).getAllByRole('listitem').map((item) => item.textContent)).toEqual([
      '1,284Documents91 in the last 7 days',
      '14 GBStored310 MB in the last 7 days',
      '12Matters1 in the last 30 days',
      '7Clients0 in the last 30 days',
      '6People1 in the last 30 days4 signed in now',
    ])
  })

  it('says when it was worked out', async () => {
    await renderApp('/')
    const part = await region('Usage')
    expect(await within(part).findByText(/^As of /)).toBeTruthy()
  })

  it('names matters as the firm does', async () => {
    setLabels({ matter: 'Project', matters: 'Projects' })
    await renderApp('/')
    const part = await region('Usage')
    const totals = await within(part).findByRole('list', { name: 'Totals' })
    expect(within(totals).getByText('Projects')).toBeTruthy()
    expect(within(part).getByRole('heading', { name: 'Busiest Projects This Week' })).toBeTruthy()
  })

  it('draws the documents filed in the last 30 days as a chart, with its numbers in a table a screen reader reads', async () => {
    await renderApp('/')
    const chart = await screen.findByRole('img', { name: 'Documents Filed, Last 30 Days' })
    expect(chart.tagName.toLowerCase()).toBe('svg')
    expect(chart.querySelectorAll('[data-bar]')).toHaveLength(30)
    const table = screen.getByRole('table', { name: 'Documents Filed, Last 30 Days' })
    expect(within(table).getAllByRole('row')).toHaveLength(31)
    expect(within(table).getAllByRole('columnheader').map((cell) => cell.textContent)).toEqual(['Day', 'Documents'])
    expect(within(table).getAllByRole('row')[30]!.textContent).toBe('Nov 19, 20267')
  })

  it('has Storage by Store, each with its size, backend and documents, and the Busiest Matters This Week', async () => {
    await renderApp('/')
    const part = await region('Usage')
    const stores = await within(part).findByRole('list', { name: 'Storage by Store' })
    expect(within(stores).getAllByRole('listitem').map((item) => item.textContent)).toEqual([
      'Client Work12 GB on Wasabi EU, encrypted · 1,004 documents',
      'Firm2.4 GB on Original · 280 documents',
    ])
    const busiest = within(part).getByRole('list', { name: 'Busiest Matters This Week' })
    expect(within(busiest).getAllByRole('listitem').map((item) => item.textContent)).toEqual([
      '1001.1 – Charter Dispute14 filed',
      '1003.1 – Series A Investment9 filed',
      '1002.2 – Employment Tribunal6 filed',
    ])
  })

  it('says it hasn\'t been worked out yet after a start, and still how many are signed in', async () => {
    const { view } = await renderApp('/', { 'GET /v1/admin/dashboard/usage': usageNotYet })
    const part = await region('Usage')
    expect(await within(part).findByText("Usage hasn't been worked out since the server started. It shows here within a few minutes.")).toBeTruthy()
    expect(within(part).getByText('1 signed in now')).toBeTruthy()
    expect(within(part).queryByRole('img')).toBeNull()
    await expectNoAxeViolations(view.container)
  })

  it('keeps the last figures when it couldn\'t be worked out again, and says since when', async () => {
    await renderApp('/', { 'GET /v1/admin/dashboard/usage': { ...usage, failedAt: '2026-11-19T11:12:00Z' } })
    const part = await region('Usage')
    expect(await within(part).findByText(/^Usage couldn't be worked out since .+\. These are the figures from then\.$/)).toBeTruthy()
    expect(within(part).queryByText(/^As of /)).toBeNull()
    expect(within(part).getByRole('list', { name: 'Totals' })).toBeTruthy()
  })

  it('says it couldn\'t be worked out when it never has been', async () => {
    await renderApp('/', { 'GET /v1/admin/dashboard/usage': { ...usageNotYet, failedAt: '2026-11-19T11:12:00Z' } })
    const part = await region('Usage')
    expect(await within(part).findByText("Usage couldn't be worked out.")).toBeTruthy()
    expect(within(part).queryByText(/hasn't been worked out since the server started/)).toBeNull()
  })

  it('says when nothing was filed this week, and when there are no Stores', async () => {
    await renderApp('/', { 'GET /v1/admin/dashboard/usage': { ...usage, stores: [], busiestMatters: [] } })
    const part = await region('Usage')
    expect(await within(part).findByText('Nothing was filed in the last 7 days.')).toBeTruthy()
    expect(within(part).getByText('None yet.')).toBeTruthy()
  })
})

describe('A part that fails', () => {
  it('shows its own error, and the other parts draw', async () => {
    await renderApp('/', { 'GET /v1/admin/dashboard/usage': problem(500, 'server-error') })
    const usagePart = await region('Usage')
    expect(await within(usagePart).findByText('Something went wrong.')).toBeTruthy()
    const box = await region('Needs Attention')
    expect(await within(box).findByText('3 files are stuck moving to storage')).toBeTruthy()
    expect(within(await region('Server Health')).getAllByRole('listitem')).toHaveLength(10)
  })

  it('leaves Usage drawn when Needs Attention and Server Health fail', async () => {
    await renderApp('/', { 'GET /v1/admin/dashboard': problem(503, 'server-error') })
    const usagePart = await region('Usage')
    expect(await within(usagePart).findByRole('list', { name: 'Totals' })).toBeTruthy()
    expect(within(await region('Needs Attention')).getByText('Something went wrong.')).toBeTruthy()
  })
})

// Spec 7.2: the Dashboard asks again every 30 seconds while visible, and data refreshes when the window comes back
describe('Asking again', () => {
  async function waitSeconds(seconds: number) {
    await act(async () => void vi.advanceTimersByTime(seconds * 1000))
  }

  // A moment for what was to be asked to be asked. Testing Library's waitFor checks again on an interval, which these
  // tests fake, so they wait on a real timeout instead.
  const settle = () => act(() => new Promise((resolve) => setTimeout(resolve, 20)))

  async function polling() {
    // Only the polls' intervals are moved on by hand; the app's other waits run as they do
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
    const rendered = await renderApp('/')
    await region('Needs Attention')
    await region('Usage')
    await settle()
    expect(rendered.api.callsTo('GET /v1/admin/dashboard/usage')).toHaveLength(1)
    return rendered
  }

  it('asks for every part every 30 seconds while the page is visible', async () => {
    const { api } = await polling()
    expect(api.callsTo('GET /v1/admin/dashboard')).toHaveLength(1)
    await waitSeconds(30)
    expect(api.callsTo('GET /v1/admin/dashboard')).toHaveLength(2)
    expect(api.callsTo('GET /v1/admin/dashboard/usage')).toHaveLength(2)
    await waitSeconds(30)
    expect(api.callsTo('GET /v1/admin/dashboard')).toHaveLength(3)
    expect(api.callsTo('GET /v1/admin/dashboard/usage')).toHaveLength(3)
  })

  it('asks nothing while the page is hidden, and asks again when the person comes back', async () => {
    const { api } = await polling()
    hidePage()
    await waitSeconds(120)
    expect(api.callsTo('GET /v1/admin/dashboard')).toHaveLength(1)
    expect(api.callsTo('GET /v1/admin/dashboard/usage')).toHaveLength(1)

    returnToPage()
    await settle()
    expect(api.callsTo('GET /v1/admin/dashboard')).toHaveLength(2)
    expect(api.callsTo('GET /v1/admin/dashboard/usage')).toHaveLength(2)
    // And the polls go on
    await waitSeconds(30)
    expect(api.callsTo('GET /v1/admin/dashboard')).toHaveLength(3)
  })

  it('asks again when the person comes back to the window, however fresh the answer', async () => {
    const { api } = await polling()
    await act(async () => void window.dispatchEvent(new Event('focus')))
    await settle()
    expect(api.callsTo('GET /v1/admin/dashboard')).toHaveLength(2)
    expect(api.callsTo('GET /v1/admin/dashboard/usage')).toHaveLength(2)
  })

  it('stops when the Dashboard is left', async () => {
    const { api, router } = await polling()
    act(() => router.history.push('/admin/no-such-page'))
    await screen.findByRole('heading', { level: 1, name: 'Page Not Found' })
    await waitSeconds(120)
    expect(api.callsTo('GET /v1/admin/dashboard')).toHaveLength(1)
  })
})
