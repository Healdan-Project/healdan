// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import type { components } from '@healdan/api'
import { expectNoAxeViolations } from '../test/axe'
import { children, document, folder, problem, type Routes } from '../test/fakeApi'
import { detailsRoutes } from '../test/details'
import { renderApp } from '../test/renderApp'

type S = components['schemas']

const correspondence = folder({
  id: 7,
  name: 'Correspondence',
  path: [
    { id: 2, name: 'Smoke', kind: 'store' },
    { id: 110, name: '1001.1 – Charter Dispute', kind: 'matter' },
    { id: 7, name: 'Correspondence', kind: 'folder' },
  ],
})

const pnair = { username: 'pnair', displayName: 'Priya Nair' }
const jclarke = { username: 'jclarke', displayName: 'Jamie Clarke' }
const pdfs = [
  document({ docNumber: 10388, name: 'Letter from Dunmere Pryce.pdf', author: jclarke }),
  document({ docNumber: 10461, name: 'Notice of Arbitration.pdf', author: jclarke }),
  document({ docNumber: 10462, name: 'Claim.pdf', author: pnair }),
]
const words = Array.from({ length: 4 }, (_, i) => document({ docNumber: 10500 + i, name: `Reply ${i + 1}.docx`, author: pnair }))
const all = [...pdfs, ...words]

const facets: S['Facets'] = {
  type: [
    { value: 'word', count: 4 },
    { value: 'pdf', count: 3 },
  ],
  author: [
    { value: 'pnair', displayName: 'Priya Nair', count: 5 },
    { value: 'jclarke', displayName: 'Jamie Clarke', count: 2 },
  ],
  class: [{ value: 'Correspondence', count: 7 }],
}

const asks = (query: Record<string, unknown> | undefined, name: string) => query?.[name]

// The folder's children as the API answers them: filtered by type, with the facets only when asked for.
function routes(over: Routes = {}): Routes {
  return {
    'GET /v1/folders/7': correspondence,
    ...detailsRoutes(pdfs[1]!),
    'GET /v1/folders/7/children': ({ query }: { query?: Record<string, unknown> }) => {
      const kinds = asks(query, 'type') as string[] | undefined
      const authors = asks(query, 'author') as string[] | undefined
      let kept = all
      if (kinds) kept = kept.filter((d) => (kinds.includes('pdf') && d.name.endsWith('.pdf')) || (kinds.includes('word') && d.name.endsWith('.docx')))
      if (authors) kept = kept.filter((d) => authors.includes(d.author?.username ?? ''))
      return { ...children(kept, { limit: asks(query, 'limit') as number }), ...(asks(query, 'facets') ? { facets } : {}) }
    },
    ...over,
  }
}

const main = () => screen.getByRole('main')
const dataRows = async () => within(await within(main()).findByRole('grid')).getAllByRole('row').slice(1)
const funnel = () => within(main()).getByRole('button', { name: /^Filters/ })
const panel = () => screen.queryByRole('region', { name: 'Filters' })

async function open(path = '/folders/7', over: Routes = {}) {
  const rendered = await renderApp(path, routes(over))
  await within(main()).findByText(/documents?$/, undefined, { timeout: 3000 }).catch(() => undefined)
  return rendered
}

describe('Filters in a folder', () => {
  it('opens from the funnel, which is pressed while they show, and the list takes the width back when they close', async () => {
    await open()
    expect(await within(main()).findByText('7 documents')).toBeTruthy()
    expect(await dataRows()).toHaveLength(7)
    // Nothing selected and Filters closed: no region
    expect(panel()).toBeNull()
    expect(screen.queryByRole('region', { name: 'Details' })).toBeNull()
    expect(funnel().getAttribute('aria-pressed')).toBe('false')

    await userEvent.click(funnel())
    const region = await screen.findByRole('region', { name: 'Filters' })
    expect(funnel().getAttribute('aria-pressed')).toBe('true')
    expect(within(region).getByRole('radiogroup', { name: 'Edit Date' })).toBeTruthy()
    // Each option with its count (File Type, Author, Class); no Client or Matter inside a folder
    expect(await within(region).findByRole('checkbox', { name: 'Word, 4' })).toBeTruthy()
    expect(within(region).getByRole('checkbox', { name: 'PDF, 3' })).toBeTruthy()
    expect(within(region).getByRole('checkbox', { name: 'Priya Nair, 5' })).toBeTruthy()
    expect(within(region).getByRole('checkbox', { name: 'Correspondence, 7' })).toBeTruthy()
    expect(within(region).queryByRole('group', { name: 'Client' })).toBeNull()
    expect(within(region).queryByRole('group', { name: 'Matter' })).toBeNull()

    await userEvent.click(funnel())
    expect(panel()).toBeNull()
  })

  it('closes from its own button, and focus goes back to the funnel', async () => {
    await open()
    await userEvent.click(funnel())
    await userEvent.click(await screen.findByRole('button', { name: 'Close Filters' }))
    expect(panel()).toBeNull()
    await waitFor(() => expect(globalThis.document.activeElement).toBe(funnel()))
  })

  it('ticking an option updates the address and the list, with a chip, the badge and "3 of 7 documents"', async () => {
    const { api, router } = await open()
    await userEvent.click(funnel())
    await userEvent.click(await screen.findByRole('checkbox', { name: 'PDF, 3' }))

    await waitFor(async () => expect(await dataRows()).toHaveLength(3))
    expect(router.history.location.search).toBe('?type=pdf')
    expect(await within(main()).findByText('3 of 7 documents')).toBeTruthy()
    expect(within(main()).getByText('File Type: PDF')).toBeTruthy()
    expect(funnel().getAttribute('aria-label')).toBe('Filters 1 in Use')
    // The list is asked for the filter; "of 7" comes from one more request without it
    expect(api.callsTo('GET /v1/folders/7/children').some((c) => (c.query?.type as string[] | undefined)?.[0] === 'pdf' && c.query?.limit === 100)).toBe(true)
    expect(api.callsTo('GET /v1/folders/7/children').some((c) => c.query?.limit === 1 && !c.query?.type && !c.query?.facets)).toBe(true)
  })

  it('asks nothing extra to count "of" while no filter is in use', async () => {
    const { api } = await open()
    expect(await within(main()).findByText('7 documents')).toBeTruthy()
    expect(api.callsTo('GET /v1/folders/7/children').filter((c) => c.query?.limit === 1)).toEqual([])
  })

  it('takes several values of one filter, and Clear All drops everything', async () => {
    const { api, router } = await open()
    await userEvent.click(funnel())
    await userEvent.click(await screen.findByRole('checkbox', { name: 'PDF, 3' }))
    await userEvent.click(await screen.findByRole('checkbox', { name: 'Word, 4' }))
    await userEvent.click(await screen.findByRole('checkbox', { name: 'Priya Nair, 5' }))
    await waitFor(() => expect(router.history.location.search).toBe('?type=pdf&type=word&author=pnair'))
    expect(within(main()).getByText('File Type: PDF, Word')).toBeTruthy()
    expect(within(main()).getByText('Author: Priya Nair')).toBeTruthy()
    expect(funnel().getAttribute('aria-label')).toBe('Filters 2 in Use')
    expect(api.callsTo('GET /v1/folders/7/children').some((c) => (c.query?.type as string[] | undefined)?.join() === 'pdf,word')).toBe(true)

    await userEvent.click(within(main()).getByRole('button', { name: 'Clear All' }))
    await waitFor(() => expect(router.history.location.search).toBe(''))
    expect(within(main()).queryByRole('group', { name: 'Filters in Use' })).toBeNull()
    expect(await within(main()).findByText('7 documents')).toBeTruthy()
    await waitFor(async () => expect(await dataRows()).toHaveLength(7), { timeout: 3000 })
  })

  it("drops one filter with its chip's ×", async () => {
    const { router } = await open('/folders/7?type=pdf&author=pnair')
    expect(await within(main()).findByText('Author: Priya Nair')).toBeTruthy()
    await userEvent.click(within(main()).getByRole('button', { name: 'Remove Author Filter' }))
    await waitFor(() => expect(router.history.location.search).toBe('?type=pdf'))
    expect(within(main()).queryByText(/^Author:/)).toBeNull()
  })

  it('keeps the filters when Filters are closed: the chips and the badge stay', async () => {
    await open('/folders/7?type=pdf')
    expect(await within(main()).findByText('File Type: PDF')).toBeTruthy()
    expect(panel()).toBeNull()
    expect(funnel().getAttribute('aria-label')).toBe('Filters 1 in Use')
    expect(funnel().getAttribute('aria-pressed')).toBe('false')
  })

  it("sends the person's time zone with the list and its counts, so Today and the days of a range are theirs", async () => {
    const zone = Intl.DateTimeFormat().resolvedOptions().timeZone
    const { api } = await open('/folders/7?edited=today')
    await userEvent.click(funnel())
    await screen.findByRole('region', { name: 'Filters' })
    await waitFor(() => expect(api.callsTo('GET /v1/folders/7/children').some((c) => c.query?.facets)).toBe(true))
    const calls = api.callsTo('GET /v1/folders/7/children').filter((c) => c.query?.edited === 'today')
    expect(calls.length).toBeGreaterThan(1)
    for (const call of calls) expect(call.query?.zone).toBe(zone)
  })

  it('sends the Edit Date preset, and a range from Between…', async () => {
    const { api, router } = await open()
    await userEvent.click(funnel())
    await userEvent.click(await screen.findByRole('radio', { name: 'Last 7 Days' }))
    await waitFor(() => expect(router.history.location.search).toBe('?edited=7d'))
    expect(within(main()).getByText('Edit Date: Last 7 Days')).toBeTruthy()
    await waitFor(() => expect(api.callsTo('GET /v1/folders/7/children').some((c) => c.query?.edited === '7d')).toBe(true))

    await userEvent.click(screen.getByRole('radio', { name: 'Between…' }))
    await userEvent.click(within(screen.getByRole('group', { name: 'From' })).getByRole('spinbutton', { name: /month/i }))
    await userEvent.keyboard('11012026')
    await waitFor(() => expect(router.history.location.search).toBe('?from=2026-11-01'))
    expect(within(main()).getByText('Edit Date: From Nov 1, 2026')).toBeTruthy()
    expect(api.callsTo('GET /v1/folders/7/children').some((c) => c.query?.from === '2026-11-01' && c.query?.edited === undefined)).toBe(true)
  })

  it('shows a filter from the address that no option offers as a chip, so nothing hides', async () => {
    await open('/folders/7?author=gone&class=Docs')
    expect(await within(main()).findByText('Author: gone')).toBeTruthy()
    expect(within(main()).getByText('Class: Docs')).toBeTruthy()
    await userEvent.click(funnel())
    expect((await screen.findByRole('checkbox', { name: 'gone, 0' }) as HTMLInputElement).checked).toBe(true)
  })

  it('says so when the filters leave nothing', async () => {
    await open('/folders/7?author=nobody', {
      'GET /v1/folders/7/children': ({ query }: { query?: Record<string, unknown> }) =>
        asks(query, 'author') ? children([], { total: 0, documents: 0 }) : children(all),
    })
    expect(await within(main()).findByText('No documents match the filters.')).toBeTruthy()
    expect(await within(main()).findByText('0 of 7 documents')).toBeTruthy()
  })

  it("shows the selected document's details when a row is clicked with Filters open, and the funnel brings Filters back", async () => {
    await open('/folders/7?type=pdf')
    await userEvent.click(funnel())
    expect(await screen.findByRole('region', { name: 'Filters' })).toBeTruthy()

    await userEvent.click((await dataRows())[1]!)
    const details = await screen.findByRole('region', { name: 'Details' })
    expect(within(details).getByText('Notice of Arbitration.pdf')).toBeTruthy()
    expect(within(details).getByText(/^# 10461 · Version 1 · /)).toBeTruthy()
    expect(panel()).toBeNull()
    // The filters count as set while the panel is away
    expect(within(main()).getByText('File Type: PDF')).toBeTruthy()
    expect(funnel().getAttribute('aria-label')).toBe('Filters 1 in Use')
    expect(funnel().getAttribute('aria-pressed')).toBe('false')

    await userEvent.click(funnel())
    expect(await screen.findByRole('region', { name: 'Filters' })).toBeTruthy()
    expect(screen.queryByRole('region', { name: 'Details' })).toBeNull()
    // Back to a row: its details again
    await userEvent.click((await dataRows())[0]!)
    expect(await screen.findByRole('region', { name: 'Details' })).toBeTruthy()
  })

  it('leaves Filters open when a right-click selects a row for its menu', async () => {
    await open('/folders/7?type=pdf')
    await userEvent.click(funnel())
    expect(await screen.findByRole('region', { name: 'Filters' })).toBeTruthy()

    const target = (await dataRows())[1]!
    fireEvent.contextMenu(target)
    const menu = await screen.findByRole('menu')
    await waitFor(() => expect(target.getAttribute('aria-selected')).toBe('true'))
    fireEvent.keyDown(menu, { key: 'Escape' })
    await waitFor(() => expect(screen.queryByRole('menu')).toBeNull())
    expect(await screen.findByRole('region', { name: 'Filters' })).toBeTruthy()
    expect(screen.queryByRole('region', { name: 'Details' })).toBeNull()
  })

  it("edits one end of a range without the other being cleared or garbled, typed segment by segment", async () => {
    const { router } = await open('/folders/7?from=2026-11-01&to=2026-11-09')
    await userEvent.click(funnel())
    const to = await screen.findByRole('group', { name: 'To' })
    // 11 to 12 passes through January, which would end before the start: nothing is sent until it is a good range
    await userEvent.click(within(to).getByRole('spinbutton', { name: /month/i }))
    await userEvent.keyboard('12')
    await waitFor(() => expect(router.history.location.search).toBe('?from=2026-11-01&to=2026-12-09'))
    expect(within(to).getByRole('spinbutton', { name: /month/i }).textContent).toBe('12')
    expect(within(to).getByRole('spinbutton', { name: /day/i }).textContent).toBe('9')
    expect(within(to).getByRole('spinbutton', { name: /year/i }).textContent).toBe('2026')
    expect(screen.queryByText('The end date is before the start date.')).toBeNull()
  })

  it('says so, and changes nothing in the address, when a range ends before it starts', async () => {
    const { router } = await open('/folders/7?from=2026-11-01&to=2026-11-09')
    await userEvent.click(funnel())
    const from = await screen.findByRole('group', { name: 'From' })
    await userEvent.click(within(from).getByRole('spinbutton', { name: /day/i }))
    await userEvent.keyboard('15')
    const message = await screen.findByText('The end date is before the start date.')
    expect(message).toBeTruthy()
    expect(router.history.location.search).toBe('?from=2026-11-01&to=2026-11-09')
    // The other end is as it was, not blanked, and the message is tied to the field
    const to = screen.getByRole('group', { name: 'To' })
    expect(within(to).getByRole('spinbutton', { name: /day/i }).textContent).toBe('9')
    expect(to.closest('[aria-describedby]') ?? to.parentElement?.querySelector('[aria-describedby]')).toBeTruthy()
    expect(within(from).getByRole('spinbutton', { name: /day/i }).textContent).toBe('15')
    // Fixing the other end makes a good range, which is sent
    await userEvent.click(within(to).getByRole('spinbutton', { name: /day/i }))
    await userEvent.keyboard('20')
    await waitFor(() => expect(router.history.location.search).toBe('?from=2026-11-15&to=2026-11-20'))
    expect(screen.queryByText('The end date is before the start date.')).toBeNull()
  })

  it('moves focus to the next chip, else the previous, else the funnel, when a chip is dropped', async () => {
    await open('/folders/7?type=pdf&author=pnair&class=Docs')
    await within(main()).findByText('Author: Priya Nair')
    await userEvent.click(within(main()).getByRole('button', { name: 'Remove Author Filter' }))
    await waitFor(() => expect(globalThis.document.activeElement).toBe(within(main()).getByRole('button', { name: 'Remove Class Filter' })))
    await userEvent.click(within(main()).getByRole('button', { name: 'Remove Class Filter' }))
    await waitFor(() => expect(globalThis.document.activeElement).toBe(within(main()).getByRole('button', { name: 'Remove File Type Filter' })))
    await userEvent.click(within(main()).getByRole('button', { name: 'Remove File Type Filter' }))
    await waitFor(() => expect(globalThis.document.activeElement).toBe(funnel()))
  })

  it('moves focus to the funnel after Clear All', async () => {
    await open('/folders/7?type=pdf&author=pnair')
    await userEvent.click(await within(main()).findByRole('button', { name: 'Clear All' }))
    await waitFor(() => expect(within(main()).queryByRole('button', { name: 'Clear All' })).toBeNull())
    expect(globalThis.document.activeElement).toBe(funnel())
  })

  it('shows no count while the rows are about to change, and the list looks busy', async () => {
    let release: () => void = () => {}
    const gate = new Promise<void>((resolve) => (release = resolve))
    const { router } = await open('/folders/7?type=pdf', {
      'GET /v1/folders/7/children': async ({ query }: { query?: Record<string, unknown> }) => {
        const kinds = asks(query, 'type') as string[] | undefined
        if (kinds?.length === 2 && asks(query, 'limit') === 100) await gate
        const kept = kinds?.length === 2 ? all : kinds ? pdfs : all
        return { ...children(kept, { limit: asks(query, 'limit') as number }), ...(asks(query, 'facets') ? { facets } : {}) }
      },
    })
    expect(await within(main()).findByText('3 of 7 documents')).toBeTruthy()
    await userEvent.click(funnel())
    await userEvent.click(await screen.findByRole('checkbox', { name: /^Word/ }))
    await waitFor(() => expect(router.history.location.search).toBe('?type=pdf&type=word'))
    // The old rows are still on screen, but "3 of 7" isn't the count of what is being asked for
    expect(within(main()).queryByText('3 of 7 documents')).toBeNull()
    expect(within(main()).getByRole('status').textContent).toBe('')
    expect(within(main()).getByRole('grid').closest('[aria-busy="true"]')).toBeTruthy()
    release()
    expect(await within(main()).findByText('7 of 7 documents')).toBeTruthy()
  })

  it("keeps the last answer's groups and counts on screen, dimmed, while a recount is held, never zeros", async () => {
    let release: () => void = () => {}
    const gate = new Promise<void>((resolve) => (release = resolve))
    await open('/folders/7', {
      'GET /v1/folders/7/children': async ({ query }: { query?: Record<string, unknown> }) => {
        const kinds = asks(query, 'type') as string[] | undefined
        // The recount for the ticked type is held; the list and the first count answer at once
        if (kinds && asks(query, 'facets')) await gate
        const kept = kinds ? words : all
        return { ...children(kept, { limit: asks(query, 'limit') as number }), ...(asks(query, 'facets') ? { facets } : {}) }
      },
    })
    await userEvent.click(funnel())
    const filters = await screen.findByRole('region', { name: 'Filters' })
    await within(filters).findByRole('checkbox', { name: /^Word, 4/ })
    await userEvent.click(within(filters).getByRole('checkbox', { name: /^Word/ }))
    // While the recount is held: still the same groups and counts, marked busy
    await waitFor(() => expect(filters.querySelector('[aria-busy="true"]')).toBeTruthy())
    expect(within(filters).getByRole('checkbox', { name: /^Word, 4/ })).toBeTruthy()
    expect(within(filters).getByRole('checkbox', { name: /^PDF, 3/ })).toBeTruthy()
    expect(within(filters).getByRole('group', { name: 'Author' })).toBeTruthy()
    expect(within(filters).getByRole('group', { name: 'Class' })).toBeTruthy()
    expect([...filters.querySelectorAll('[data-count]')].map((one) => one.textContent)).not.toContain('0')
    release()
    await waitFor(() => expect(filters.querySelector('[aria-busy="true"]')).toBeNull())
  })

  it('shows the filtered count alone when the count without filters could not be had', async () => {
    await open('/folders/7?type=pdf', {
      'GET /v1/folders/7/children': ({ query }: { query?: Record<string, unknown> }) => {
        if (asks(query, 'limit') === 1 && !asks(query, 'type')) return problem(500, 'server-error')
        return children(pdfs, { limit: asks(query, 'limit') as number })
      },
    })
    expect(await within(main()).findByText('3 documents')).toBeTruthy()
  })

  it('puts Between… away when the range goes, so Any Time shows after Clear All', async () => {
    const { router } = await open('/folders/7')
    await userEvent.click(funnel())
    await userEvent.click(await screen.findByRole('radio', { name: 'Between…' }))
    await userEvent.click(within(screen.getByRole('group', { name: 'From' })).getByRole('spinbutton', { name: /month/i }))
    await userEvent.keyboard('11012026')
    await waitFor(() => expect(router.history.location.search).toBe('?from=2026-11-01'))
    await userEvent.click(within(main()).getByRole('button', { name: 'Clear All' }))
    await waitFor(() => expect((screen.getByRole('radio', { name: 'Any Time' }) as HTMLInputElement).checked).toBe(true))
    expect(screen.queryByRole('group', { name: 'From' })).toBeNull()
  })

  it('keeps Filters showing when several documents are selected, and shows details for exactly one', async () => {
    await open('/folders/7')
    await userEvent.click(funnel())
    await screen.findByRole('region', { name: 'Filters' })
    const rows = await dataRows()
    await userEvent.click(rows[0]!)
    await screen.findByRole('region', { name: 'Details' })
    await userEvent.click(funnel())
    // Select All, from the list
    await userEvent.click(within((await dataRows())[0]!).getByRole('rowheader'))
    await userEvent.keyboard('{Control>}a{/Control}')
    await waitFor(async () => expect((await dataRows()).every((r) => r.getAttribute('aria-selected') === 'true')).toBe(true))
    expect(screen.getByRole('region', { name: 'Filters' })).toBeTruthy()
  })

  it('names each count line and the chips in the catalogue, listing three values with commas', async () => {
    await open('/folders/7?class=A&class=B&class=C')
    expect(await within(main()).findByText('Class: A, B, C')).toBeTruthy()
  })

  it('passes axe with Filters open', async () => {
    const { view } = await open('/folders/7?type=pdf')
    await userEvent.click(funnel())
    await within(await screen.findByRole('region', { name: 'Filters' })).findByRole('checkbox', { name: 'Word, 4' })
    await expectNoAxeViolations(view.container)
  })
})

