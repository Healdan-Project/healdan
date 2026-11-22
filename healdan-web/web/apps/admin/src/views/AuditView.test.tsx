// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import type { adminComponents } from '@healdan/api'
import { formatDateTime } from '@healdan/ui'
import { FOLLOW_SHORT_PAGES } from '../lists/useCursorList'
import { AUDIT_PAGE_SIZE } from '../monitoring/queries'
import { fullTime } from '../monitoring/text'
import { TYPING_DELAY_MS } from '../pickers/typing'
import { expectNoAxeViolations } from '../test/axe'
import { auditPage, auditRoutes, auditRow, auditRows, auditTrail, problem, type FakeApi, type Routes } from '../test/fakeApi'
import { bringObservedIntoView } from '../test/intersection'
import { renderApp } from '../test/renderApp'
import { auditSearch } from './AuditView'

type A = adminComponents['schemas']

const ZONE = Intl.DateTimeFormat().resolvedOptions().timeZone
const pane = () => screen.getByRole('navigation', { name: 'Navigation' })
const current = () => within(pane()).getAllByRole('link').filter((link) => link.getAttribute('aria-current') === 'page')
const main = () => screen.getByRole('main')
const table = () => within(main()).getByRole('grid', { name: 'Audit Trail' })
const rows = () => within(table()).getAllByRole('row').slice(1)
/** Each cell of a row, as text */
const cellsOf = (row: HTMLElement) => Array.from(row.querySelectorAll('[role="rowheader"], [role="gridcell"]'), (cell) => cell.textContent)
const whats = () => rows().map((row) => cellsOf(row)[3])
const details = () => within(main()).getByRole('region', { name: 'Row Details' })
/** The details' facts, each term with what it says */
const facts = () => Array.from(details().querySelectorAll('dt'), (term) => [term.textContent, term.nextElementSibling?.textContent])
const kinds = () => within(main()).getByRole('group', { name: 'Kind' })
const box = (name: string) => within(main()).getByRole('combobox', { name }) as HTMLInputElement
const words = () => within(main()).getByRole('textbox', { name: 'Doc #, Name or Address' }) as HTMLInputElement
const pause = () => act(() => new Promise((resolve) => setTimeout(resolve, TYPING_DELAY_MS + 100)))
/** What each request for the list asked */
const asked = (api: FakeApi) => api.callsTo('GET /v1/admin/audit').map((call) => call.query)
const STALE = "Couldn't refresh this, so it shows what was read last."

/** The Audit Trail at `path`, once its first rows have come */
async function openAudit(path = '/audit', routes: Routes = {}) {
  const rendered = await renderApp(path, { ...auditRoutes(), ...routes })
  await within(main()).findByRole('grid', { name: 'Audit Trail' })
  await waitFor(() => expect(asked(rendered.api).length).toBeGreaterThan(0))
  return rendered
}

/** `count` document rows, numbered from `from`, as a page of the server's holds them */
function documents(from: number, count: number): A['AuditRow'][] {
  return Array.from({ length: count }, (_, i) => auditRow({ id: `d${from + i}`, detail: `# ${from + i}, Note ${from + i}.docx, version 1.0`, docNumber: from + i }))
}

/** The Audit Trail's route, answering each cursor with its page from `pages`, the first page under '' */
function byCursor(pages: Record<string, A['AuditPage'] | Error>): Routes {
  return { 'GET /v1/admin/audit': ({ query }: { query?: Record<string, unknown> }) => pages[String(query?.cursor ?? '')] ?? problem(400, 'bad-request') }
}

describe('The Audit Trail', () => {
  it('opens at /admin/audit under its heading, the open screen in the pane', async () => {
    const { view } = await openAudit()
    expect(within(main()).getByRole('heading', { level: 1, name: 'Audit Trail' })).toBeTruthy()
    expect(current()).toEqual([within(pane()).getByRole('link', { name: 'Audit Trail' })])
    await expectNoAxeViolations(view.container)
  })

  it('reads its kinds, each repeated, the person, the matter, the doc #, the days and the words from the address', async () => {
    const { router } = await renderApp('/audit?kind=folder&kind=document&user=6&matter=11&doc=10388&from=2026-11-01&to=2026-11-19&q=%20192.0.2%20', auditRoutes())
    expect(router.state.matches.at(-1)?.search).toMatchObject({ kind: ['document', 'folder'], user: 6, matter: 11, doc: 10388, from: '2026-11-01', to: '2026-11-19', q: '192.0.2' })
  })

  it('reads one kind as a list of one', async () => {
    const { router } = await renderApp('/audit?kind=sign-in', auditRoutes())
    expect(router.state.matches.at(-1)?.search).toMatchObject({ kind: ['sign-in'] })
  })

  it("keeps each kind once, in the server's order, and leaves out what isn't one of its values", () => {
    expect(auditSearch({ kind: ['administration', 'nope', 'sign-in', 'administration'] })).toEqual({ kind: ['sign-in', 'administration'] })
    expect(auditSearch({ kind: ['nope'], user: '-6', matter: '1.5', doc: '# 10388', from: '2026-02-30', to: '2026-1-5', q: '  ' })).toEqual({})
    expect(auditSearch({ kind: 'user', user: 6, matter: 11, doc: 10388, to: '2026-11-19' })).toEqual({ kind: ['user'], user: 6, matter: 11, doc: 10388, to: '2026-11-19' })
  })
})

describe("The Audit Trail's list", () => {
  it('shows what was done, newest first, as Time, Who, Kind, What, Detail and From, asked with the browser’s time zone', async () => {
    const { api, view } = await openAudit()
    expect(within(table()).getAllByRole('columnheader').map((heading) => heading.textContent)).toEqual(['Time', 'Who', 'Kind', 'What', 'Detail', 'From'])
    expect(rows().map(cellsOf)).toEqual([
      [formatDateTime('2026-11-19T10:44:07.120Z'), 'Sam Okafor', 'Administration', 'Changed a storage backend', 'Wasabi EU · name: Wasabi → Wasabi EU · secret: changed', '192.0.2.9'],
      [formatDateTime('2026-11-19T10:39:00Z'), 'Priya Nair', 'Sign-In', 'Signed in', 'Password, Safari on a Mac', '192.0.2.17'],
      [formatDateTime('2026-11-19T10:33:00Z'), 'Tom Reeve', 'Document', 'Downloaded', '# 10388, Skeleton Argument.docx, version 1.4', '192.0.2.12'],
      [formatDateTime('2026-11-19T10:05:00Z'), 'Helen Ashcombe', 'Folder', 'Changed permissions', '/Client Work/1001.1 Charter Dispute – MV Northern Star/Privileged', '192.0.2.11'],
      [
        formatDateTime('2026-11-19T09:58:00Z'),
        'Sam Okafor',
        'Administration',
        'Renamed a client',
        '1001 – Saltmarsh Shipping Ltd · description: (none) → Shipping and charters · name: Saltmarsh Shipping → Saltmarsh Shipping Ltd',
        '192.0.2.9',
      ],
      // A row that names no one, and one with no address
      [formatDateTime('2026-11-19T09:47:00Z'), '', 'Sign-In', 'Failed to sign in', '', '192.0.2.21'],
      [formatDateTime('2026-11-19T09:12:00Z'), 'Jamie Clarke', 'User', 'Changed their password', '', ''],
    ])
    // Every filter left out but the zone, and the page size
    expect(asked(api)).toEqual([{ zone: ZONE, limit: AUDIT_PAGE_SIZE }])
    // Every row came, so the end says so
    expect(within(main()).getByText('No older rows.')).toBeTruthy()
    expect(within(main()).queryByRole('button', { name: 'Show More' })).toBeNull()
    await expectNoAxeViolations(view.container)
  })

  it("draws a row's words as plain text, never as markup, in its cells and its details", async () => {
    const hostile = auditRow({ id: 'd9', who: '<img src=x onerror="alert(1)">', what: '<b>Downloaded</b>', detail: '<script>alert(1)</script> Note.docx', from: '<i>192.0.2.1</i>' })
    const { view } = await openAudit('/audit', { 'GET /v1/admin/audit': auditPage([hostile]) })
    await waitFor(() => expect(rows()).toHaveLength(1))
    expect(cellsOf(rows()[0]!).slice(1)).toEqual(['<img src=x onerror="alert(1)">', 'Document', '<b>Downloaded</b>', '<script>alert(1)</script> Note.docx', '<i>192.0.2.1</i>'])
    // No link in a row, as a link inside a row fires both
    expect(within(table()).queryAllByRole('link')).toEqual([])
    await userEvent.click(rows()[0]!.querySelector('[role="rowheader"]')!)
    expect(within(details()).getByText('<script>alert(1)</script> Note.docx')).toBeTruthy()
    expect(view.container.querySelector('img, b, i, script')).toBeNull()
  })

  it("keeps a long detail to the row's one line, and shows the whole of it in the row's details", async () => {
    const long = `# 10390, ${'Witness Statement of the Second Respondent '.repeat(40)}.docx, version 2.0`
    await openAudit('/audit', { 'GET /v1/admin/audit': auditPage([auditRow({ id: 'd10', detail: long })]) })
    await waitFor(() => expect(rows()).toHaveLength(1))
    const cell = rows()[0]!.querySelectorAll('[role="gridcell"]')[3]!
    // The cell holds the text alone, in the one box the list cuts short with an ellipsis
    expect(cell.children).toHaveLength(1)
    expect(cell.firstElementChild!.children).toHaveLength(0)
    expect(cell.textContent).toBe(long)
    await userEvent.click(rows()[0]!.querySelector('[role="rowheader"]')!)
    expect(facts()).toContainEqual(['Detail', long])
  })

  it('shows a selected row whole in its details: the full time, and an administration row its fields, old and new, a secret only as changed', async () => {
    const { view } = await openAudit()
    expect(within(main()).queryByRole('region', { name: 'Row Details' })).toBeNull()
    await userEvent.click(rows()[0]!.querySelector('[role="rowheader"]')!)
    expect(facts()).toEqual([
      ['When', fullTime('2026-11-19T10:44:07.120Z')],
      ['Who', 'Sam Okafor'],
      ['Kind', 'Administration'],
      ['What', 'Changed a storage backend'],
      ['Detail', 'Wasabi EU'],
      ['From', '192.0.2.9'],
    ])
    // The seconds are there, as the list's minutes aren't the whole of it
    expect(fullTime('2026-11-19T10:44:07.120Z')).toMatch(/07/)
    const changes = within(details()).getByRole('list', { name: 'Changes' })
    expect(within(changes).getAllByRole('listitem').map((item) => item.textContent)).toEqual(['name: Wasabi → Wasabi EU', 'secret: changed'])
    // No value of the secret, in any form
    expect(details().textContent).not.toMatch(/true/)
    await expectNoAxeViolations(view.container)

    // A value that was none says so
    await userEvent.click(rows()[4]!.querySelector('[role="rowheader"]')!)
    expect(within(within(details()).getByRole('list', { name: 'Changes' })).getAllByRole('listitem').map((item) => item.textContent)).toEqual([
      'description: (none) → Shipping and charters',
      'name: Saltmarsh Shipping → Saltmarsh Shipping Ltd',
    ])
    // A row that names no one and keeps no detail or address says so, and has no changes
    await userEvent.click(rows()[5]!.querySelector('[role="rowheader"]')!)
    expect(facts()).toEqual([
      ['When', fullTime('2026-11-19T09:47:00Z')],
      ['Who', 'Not recorded'],
      ['Kind', 'Sign-In'],
      ['What', 'Failed to sign in'],
      ['Detail', 'Not recorded'],
      ['From', '192.0.2.21'],
    ])
    expect(within(details()).queryByRole('list', { name: 'Changes' })).toBeNull()
  })

  it("offers the document or the folder a row names in Healdan, from the row's details, and nothing for a row that names neither", async () => {
    await openAudit()
    await userEvent.click(rows()[2]!.querySelector('[role="rowheader"]')!)
    expect(within(details()).getByRole('link', { name: 'Open Document in Healdan' }).getAttribute('href')).toBe('/app/doc/10388')
    expect(within(details()).queryByRole('link', { name: 'Open Folder in Healdan' })).toBeNull()
    await userEvent.click(rows()[3]!.querySelector('[role="rowheader"]')!)
    expect(within(details()).getByRole('link', { name: 'Open Folder in Healdan' }).getAttribute('href')).toBe('/app/folders/115')
    await userEvent.click(rows()[1]!.querySelector('[role="rowheader"]')!)
    expect(within(details()).queryAllByRole('link')).toEqual([])
  })

  it('follows the selection by the arrow keys, and Tab reaches the details and their link', async () => {
    await openAudit()
    await userEvent.click(rows()[1]!.querySelector('[role="rowheader"]')!)
    await userEvent.keyboard('{ArrowDown}')
    expect(facts()[3]).toEqual(['What', 'Downloaded'])
    const link = within(details()).getByRole('link', { name: 'Open Document in Healdan' })
    for (let i = 0; i < 20 && document.activeElement !== details(); i++) await userEvent.tab()
    expect(document.activeElement).toBe(details())
    await userEvent.tab()
    expect(document.activeElement).toBe(link)
  })

  it('loads more rows by the cursor as the end of the list comes into view, and by Show More, and says when it has them all', async () => {
    const { api } = await openAudit('/audit', byCursor({ '': auditPage(documents(1, AUDIT_PAGE_SIZE), 'b'), b: auditPage(documents(101, AUDIT_PAGE_SIZE), 'c'), c: auditPage(documents(201, 1)) }))
    await waitFor(() => expect(rows()).toHaveLength(AUDIT_PAGE_SIZE))
    expect(within(main()).queryByText('No older rows.')).toBeNull()

    act(() => bringObservedIntoView())
    await waitFor(() => expect(rows()).toHaveLength(2 * AUDIT_PAGE_SIZE))
    const more = within(main()).getByRole('button', { name: 'Show More' })
    await userEvent.click(more)
    await waitFor(() => expect(rows()).toHaveLength(2 * AUDIT_PAGE_SIZE + 1))
    expect(asked(api).map((query) => query?.cursor)).toEqual([undefined, 'b', 'c'])
    expect(asked(api).every((query) => query?.zone === ZONE && query?.limit === AUDIT_PAGE_SIZE)).toBe(true)
    // The end: Show More goes, and the focus it had goes to what says so
    const end = within(main()).getByText('No older rows.')
    expect(within(main()).queryByRole('button', { name: 'Show More' })).toBeNull()
    await waitFor(() => expect(document.activeElement).toBe(end))
  })

  it('goes on by itself over pages that come back short with a cursor, a few in a row, then offers Show More', async () => {
    const { api } = await openAudit(
      '/audit',
      byCursor({ '': auditPage(documents(1, 2), 'b'), b: auditPage([], 'c'), c: auditPage([], 'd'), d: auditPage([], 'e'), e: auditPage(documents(3, 1)) }),
    )
    const more = await within(main()).findByRole('button', { name: 'Show More' })
    expect(asked(api).map((query) => query?.cursor)).toEqual([undefined, 'b', 'c', 'd'])
    expect(asked(api)).toHaveLength(1 + FOLLOW_SHORT_PAGES)
    expect(rows()).toHaveLength(2)
    await userEvent.click(more)
    await waitFor(() => expect(rows()).toHaveLength(3))
    expect(within(main()).getByText('No older rows.')).toBeTruthy()
  })

  it('says when nothing has been found yet in what was read, and Show More reads on', async () => {
    await openAudit('/audit?q=zzz', byCursor({ '': auditPage([], 'b'), b: auditPage([], 'c'), c: auditPage([], 'd'), d: auditPage([], 'e'), e: auditPage([]) }))
    expect(await within(main()).findByText('Nothing found yet in what has been read. Show More reads further back.')).toBeTruthy()
    await userEvent.click(within(main()).getByRole('button', { name: 'Show More' }))
    expect(await within(main()).findByText('No rows match the filters.')).toBeTruthy()
  })

  it('says when nothing has been recorded, and otherwise when the filters keep nothing', async () => {
    const { view } = await openAudit('/audit', { 'GET /v1/admin/audit': auditPage([]) })
    expect(await within(main()).findByText('Nothing has been recorded yet.')).toBeTruthy()
    view.unmount()
    await openAudit('/audit?kind=user', { 'GET /v1/admin/audit': auditPage([]) })
    expect(await within(main()).findByText('No rows match the filters.')).toBeTruthy()
  })

  it("keeps the rows it has while a new read fails, and says it couldn't refresh (P4-R15)", async () => {
    let failing = false
    const { view } = await openAudit('/audit', { 'GET /v1/admin/audit': ({ query }: { query?: Record<string, unknown> }) => (failing ? problem(500, 'server-error') : auditTrail(query)) })
    await waitFor(() => expect(rows()).toHaveLength(auditRows.length))
    failing = true
    await act(async () => void window.dispatchEvent(new Event('focus')))
    expect(await screen.findByText(STALE)).toBeTruthy()
    expect(rows()).toHaveLength(auditRows.length)
    await expectNoAxeViolations(view.container)
    failing = false
    await userEvent.click(within(main()).getByRole('button', { name: 'Try Again' }))
    await waitFor(() => expect(screen.queryByText(STALE)).toBeNull())
  })

  it('says why when the first read fails', async () => {
    await renderApp('/audit', { ...auditRoutes(), 'GET /v1/admin/audit': problem(500, 'server-error') })
    expect(await screen.findByText('Something went wrong.')).toBeTruthy()
    expect(within(main()).queryByRole('grid', { name: 'Audit Trail' })).toBeNull()
  })
})

describe("The Audit Trail's filters", () => {
  it('keeps the kinds ticked in the address, in the server’s order, and sends them; none ticked is every kind', async () => {
    const { api, router, view } = await openAudit()
    await waitFor(() => expect(rows()).toHaveLength(auditRows.length))
    expect(within(kinds()).getAllByRole('checkbox').map((box) => box.closest('label')?.textContent)).toEqual(['Sign-In', 'Document', 'Folder', 'User', 'Administration'])
    await userEvent.click(within(kinds()).getByRole('checkbox', { name: 'Folder' }))
    await userEvent.click(within(kinds()).getByRole('checkbox', { name: 'Document' }))
    await waitFor(() => expect(router.state.location.search).toEqual({ kind: ['document', 'folder'] }))
    await waitFor(() => expect(whats()).toEqual(['Downloaded', 'Changed permissions']))
    expect(asked(api).at(-1)).toEqual({ kind: ['document', 'folder'], zone: ZONE, limit: AUDIT_PAGE_SIZE })
    await expectNoAxeViolations(view.container)

    await userEvent.click(within(kinds()).getByRole('checkbox', { name: 'Folder' }))
    await userEvent.click(within(kinds()).getByRole('checkbox', { name: 'Document' }))
    await waitFor(() => expect(router.state.location.search).toEqual({}))
    await waitFor(() => expect(rows()).toHaveLength(auditRows.length))
  })

  it('finds a person among the Users list, keeps them in the address, and sends their id; emptied, it keeps everyone', async () => {
    const { api, router } = await openAudit()
    await userEvent.type(box('Person'), 'nair')
    await pause()
    const list = await screen.findByRole('listbox')
    expect(api.callsTo('GET /v1/admin/users').at(-1)?.query).toEqual({ q: 'nair', limit: 20 })
    await userEvent.click(within(list).getByRole('option', { name: 'Priya Nair priya' }))
    await waitFor(() => expect(router.state.location.search).toEqual({ user: '7' }))
    await waitFor(() => expect(asked(api).at(-1)).toEqual({ userId: 7, zone: ZONE, limit: AUDIT_PAGE_SIZE }))
    expect(box('Person').value).toBe('Priya Nair')

    await userEvent.clear(box('Person'))
    await userEvent.tab()
    await waitFor(() => expect(router.state.location.search).toEqual({}))
    expect(box('Person').value).toBe('')
  })

  it('finds a matter by its number, name or client, keeps it in the address, and sends its id', async () => {
    const { api, router } = await openAudit()
    await userEvent.type(box('Matter'), 'vessel')
    await pause()
    const list = await screen.findByRole('listbox')
    await userEvent.click(within(list).getByRole('option', { name: '1001.2 – Vessel Sale Saltmarsh Shipping Ltd' }))
    await waitFor(() => expect(router.state.location.search).toEqual({ matter: '12' }))
    await waitFor(() => expect(asked(api).at(-1)).toEqual({ matterId: 12, zone: ZONE, limit: AUDIT_PAGE_SIZE }))
    expect(box('Matter').value).toBe('1001.2 – Vessel Sale')
  })

  it('names the person and the matter an address holds by id, as after a reload, asking the server for each', async () => {
    const { api } = await openAudit('/audit?user=7&matter=12')
    await waitFor(() => expect(box('Person').value).toBe('Priya Nair'))
    await waitFor(() => expect(box('Matter').value).toBe('1001.2 – Vessel Sale'))
    expect(api.callsTo('GET /v1/admin/users/7')).toHaveLength(1)
    expect(api.callsTo('GET /v1/admin/matters/12')).toHaveLength(1)
    expect(asked(api)[0]).toEqual({ userId: 7, matterId: 12, zone: ZONE, limit: AUDIT_PAGE_SIZE })
  })

  it('keeps the days typed in the address, and sends them with the time zone they are days in', async () => {
    const { api, router } = await openAudit('/audit?to=2026-11-19')
    expect(within(within(main()).getByRole('group', { name: 'To Date' })).getByRole('spinbutton', { name: /day/i }).textContent).toBe('19')
    await userEvent.click(within(within(main()).getByRole('group', { name: 'From Date' })).getByRole('spinbutton', { name: /month/i }))
    await userEvent.keyboard('11012026')
    await waitFor(() => expect(router.state.location.search).toEqual({ from: '2026-11-01', to: '2026-11-19' }))
    await waitFor(() => expect(asked(api).at(-1)).toEqual({ from: '2026-11-01', to: '2026-11-19', zone: ZONE, limit: AUDIT_PAGE_SIZE }))
  })

  it('sends a bare number typed in its words box as the doc #, and anything else as words, once the typing pauses', async () => {
    const { api, router } = await openAudit()
    await userEvent.type(words(), ' 10388 ')
    await waitFor(() => expect(router.state.location.search).toEqual({ doc: '10388' }))
    await waitFor(() => expect(asked(api).at(-1)).toEqual({ docNumber: 10388, zone: ZONE, limit: AUDIT_PAGE_SIZE }))

    await userEvent.clear(words())
    await userEvent.type(words(), '192.0.2')
    await waitFor(() => expect(router.state.location.search).toEqual({ q: '192.0.2' }))
    await waitFor(() => expect(asked(api).at(-1)).toEqual({ q: '192.0.2', zone: ZONE, limit: AUDIT_PAGE_SIZE }))
    // One question per pause, never one per key
    expect(asked(api).filter((query) => query?.q !== undefined)).toHaveLength(1)
  })

  it('takes every filter off with Clear Filters', async () => {
    const { router } = await openAudit('/audit?kind=user&user=7&matter=12&from=2026-11-01&q=reeve')
    await waitFor(() => expect(box('Person').value).toBe('Priya Nair'))
    await userEvent.click(within(main()).getByRole('button', { name: 'Clear Filters' }))
    await waitFor(() => expect(router.state.location.search).toEqual({}))
    expect(box('Person').value).toBe('')
    expect(box('Matter').value).toBe('')
    expect(words().value).toBe('')
    expect(within(kinds()).getAllByRole('checkbox').filter((one) => (one as HTMLInputElement).checked)).toEqual([])
    expect(within(main()).queryByRole('button', { name: 'Clear Filters' })).toBeNull()
    // The button went, so the focus it had goes to the words box
    expect(document.activeElement).toBe(words())
  })
})

describe('Export CSV', () => {
  /** The export link's address, as the browser reads it */
  const exported = () => {
    const link = within(main()).getByRole('link', { name: 'Export CSV' })
    expect(link.hasAttribute('download')).toBe(true)
    return new URL(link.getAttribute('href')!, window.location.href)
  }

  it('is a link to the export, same origin, with the filters in force and the time zone, never the page size or a cursor', async () => {
    await openAudit('/audit?kind=folder&kind=document&user=7&matter=12&doc=10388&from=2026-11-01&to=2026-11-19')
    const url = exported()
    expect(url.origin).toBe(window.location.origin)
    expect(url.pathname).toBe('/api/v1/admin/audit/export')
    expect([...url.searchParams]).toEqual([
      ['kind', 'document'],
      ['kind', 'folder'],
      ['userId', '7'],
      ['matterId', '12'],
      ['docNumber', '10388'],
      ['from', '2026-11-01'],
      ['to', '2026-11-19'],
      ['zone', ZONE],
    ])
  })

  it('follows the filters as they change, and carries no cursor once more rows have loaded', async () => {
    await openAudit('/audit?q=reeve', byCursor({ '': auditPage(documents(1, AUDIT_PAGE_SIZE), 'b'), b: auditPage(documents(101, 1)) }))
    expect([...exported().searchParams]).toEqual([
      ['zone', ZONE],
      ['q', 'reeve'],
    ])
    await userEvent.click(await within(main()).findByRole('button', { name: 'Show More' }))
    await within(main()).findByText('No older rows.')
    expect(exported().searchParams.has('cursor')).toBe(false)
    expect(exported().searchParams.has('limit')).toBe(false)
    await userEvent.click(within(kinds()).getByRole('checkbox', { name: 'Sign-In' }))
    await waitFor(() => expect(exported().searchParams.getAll('kind')).toEqual(['sign-in']))
  })
})
