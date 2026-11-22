// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { act, fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { adminComponents } from '@healdan/api'
import { formatDateTime } from '@healdan/ui'
import { TYPING_DELAY_MS } from '../pickers/typing'
import { expectNoAxeViolations } from '../test/axe'
import { errorDetailOf, errorDetails, errorList, errorRoutes, errorRow, errorRows, problem, type Routes } from '../test/fakeApi'
import { bringObservedIntoView } from '../test/intersection'
import { renderApp } from '../test/renderApp'
import { errorsSearch } from './ErrorsView'

type A = adminComponents['schemas']

const pane = () => screen.getByRole('navigation', { name: 'Navigation' })
const current = () => within(pane()).getAllByRole('link').filter((link) => link.getAttribute('aria-current') === 'page')
const table = () => screen.getByRole('grid', { name: 'Errors' })
const rows = () => within(table()).getAllByRole('row').slice(1)
const messages = () => rows().map((row) => within(row).getAllByRole('rowheader')[0]?.textContent)
const cells = (message: string) => {
  const row = rows().find((one) => within(one).getAllByRole('rowheader')[0]?.textContent?.startsWith(message))
  return within(row!).getAllByRole('gridcell').map((cell) => cell.textContent)
}
const details = () => screen.getByRole('region', { name: 'Error Details' })
const pause = () => act(() => new Promise((resolve) => setTimeout(resolve, TYPING_DELAY_MS + 100)))
const queries = (api: { callsTo: (pattern: string) => { query?: Record<string, unknown> }[] }) => api.callsTo('GET /v1/admin/errors').map((call) => call.query)

/**
 * axe over what's drawn, with the page asking for the light theme and then the dark one (`prefers-color-scheme`). jsdom
 * draws no colours, so this checks the markup under each; the colours themselves are tokens, which colours.test.ts checks
 * on every surface of both themes.
 */
async function expectNoAxeViolationsInBothThemes(container: Element) {
  for (const dark of [false, true]) {
    window.matchMedia = (query: string) =>
      ({ matches: dark && query.includes('prefers-color-scheme: dark'), media: query, addEventListener: () => {}, removeEventListener: () => {}, addListener: () => {}, removeListener: () => {}, onchange: null, dispatchEvent: () => false }) as unknown as MediaQueryList
    await expectNoAxeViolations(container)
  }
}

async function openErrors(path = '/errors', over: Routes = {}) {
  const rendered = await renderApp(path, { ...errorRoutes(), ...over })
  await within(await screen.findByRole('grid', { name: 'Errors' })).findAllByRole('rowheader')
  return rendered
}

/** A change the server answers only when the test says, as a slow one would be */
function slowAnswer<T>() {
  let answer!: (value: T) => void
  let refuse!: (error: unknown) => void
  const answered = new Promise<T>((resolve, reject) => {
    answer = resolve
    refuse = reject
  })
  return { answered, answer, refuse }
}

describe('Errors', () => {
  it('opens at /admin/errors under its heading, the open screen in the pane, with the count of the errors to resolve', async () => {
    const { view } = await renderApp('/errors', errorRoutes())
    expect(await screen.findByRole('heading', { level: 1, name: 'Errors' })).toBeTruthy()
    const entry = await within(pane()).findByRole('link', { name: 'Errors, 14 need attention' })
    expect(current()).toEqual([entry])
    await expectNoAxeViolations(view.container)
  })

  it('reads how far back it goes, its words, its area, Unresolved and the open fault from the address', async () => {
    const { router } = await renderApp('/errors?window=7d&q=%20timed%20out%20&area=Search%20Index&unresolved=true&id=12', errorRoutes())
    expect(router.state.matches.at(-1)?.search).toMatchObject({ window: '7d', q: 'timed out', area: 'Search Index', unresolved: true, id: 12 })
  })

  it("leaves out what isn't one of its values, and takes back what it gave the router before", () => {
    expect(errorsSearch({ window: '2h', q: '   ', area: 'search index', unresolved: 'yes', id: '-3', offset: '100' })).toEqual({})
    expect(errorsSearch({ unresolved: 'false', id: 'x' })).toEqual({})
    expect(errorsSearch({ window: '1h', area: 'Sign-In', unresolved: true, id: 12 })).toEqual({ window: '1h', area: 'Sign-In', unresolved: true, id: 12 })
  })
})

describe('The Errors list', () => {
  it('lists each kind of fault of the last 24 hours, the one last seen first: What Happened, Area, Times, First Seen and Last Seen', async () => {
    const { api, view } = await openErrors()
    expect(within(table()).getAllByRole('columnheader').map((heading) => heading.textContent)).toEqual(['What Happened', 'Area', 'Times', 'First Seen', 'Last Seen'])
    expect(messages()).toEqual([
      'Preview conversion timed out after 180 seconds, doc 10412 version 3',
      'Upload to Wasabi EU failed: connection reset\nThe mover tries again in 5 minutes.',
      "Couldn't read PDF text: the file is encrypted",
      'Outgoing email refused: authentication failed Resolved',
    ])
    expect(cells('Preview conversion')).toEqual(['Previews', '6', formatDateTime('2026-11-18T14:02:00Z'), formatDateTime('2026-11-19T10:41:00Z')])
    expect(cells("Couldn't read PDF text")[0]).toBe('Search Index')
    // The heading's count line: how many times the faults kept were seen, of how many kinds, as the server adds them up
    expect(screen.getByText('14 errors of 4 kinds')).toBeTruthy()
    expect(queries(api)).toEqual([{ window: '24h', offset: 0, limit: 100 }])
    // 24 Hours is pressed, the screen's default
    expect(screen.getByRole('radio', { name: '24 Hours' }).getAttribute('aria-checked')).toBe('true')
    await expectNoAxeViolationsInBothThemes(view.container)
  })

  it('says one error of one kind in the singular', async () => {
    await openErrors('/errors', { 'GET /v1/admin/errors': errorList(undefined, [errorRow({ times: 1 })]) })
    expect(screen.getByText('1 error of 1 kind')).toBeTruthy()
  })

  it('asks again for each window: 1 Hour, 24 Hours, 7 Days and 30 Days, kept in the address', async () => {
    const { api, router } = await openErrors()
    const group = screen.getByRole('radiogroup', { name: 'How Far Back' })
    expect(within(group).getAllByRole('radio').map((one) => one.textContent)).toEqual(['1 Hour', '24 Hours', '7 Days', '30 Days'])
    fireEvent.click(within(group).getByRole('radio', { name: '7 Days' }))
    await waitFor(() => expect(router.state.location.search).toEqual({ window: '7d' }))
    await waitFor(() => expect(queries(api).at(-1)).toEqual({ window: '7d', offset: 0, limit: 100 }))
    fireEvent.click(within(group).getByRole('radio', { name: '1 Hour' }))
    await waitFor(() => expect(queries(api).at(-1)).toEqual({ window: '1h', offset: 0, limit: 100 }))
    fireEvent.click(within(group).getByRole('radio', { name: '30 Days' }))
    await waitFor(() => expect(queries(api).at(-1)).toEqual({ window: '30d', offset: 0, limit: 100 }))
    // Back to the default, which the address leaves out
    fireEvent.click(within(group).getByRole('radio', { name: '24 Hours' }))
    await waitFor(() => expect(router.state.location.search).toEqual({}))
    expect(screen.getByRole('radio', { name: '24 Hours' }).getAttribute('aria-checked')).toBe('true')
  })

  it('filters by the words typed once the typing pauses, by area and by Unresolved, all kept in the address, and Clear Filters takes them off', async () => {
    const { api, router } = await openErrors()
    await userEvent.type(screen.getByRole('textbox', { name: 'Message or Logger' }), '  wasabi ')
    await pause()
    await waitFor(() => expect(router.state.location.search).toEqual({ q: 'wasabi' }))
    await waitFor(() => expect(queries(api).at(-1)).toEqual({ window: '24h', q: 'wasabi', offset: 0, limit: 100 }))
    await waitFor(() => expect(messages()).toHaveLength(1))

    await userEvent.clear(screen.getByRole('textbox', { name: 'Message or Logger' }))
    await pause()
    fireEvent.change(screen.getByRole('combobox', { name: 'Area' }), { target: { value: 'Email' } })
    await waitFor(() => expect(router.state.location.search).toEqual({ area: 'Email' }))
    await waitFor(() => expect(queries(api).at(-1)).toEqual({ window: '24h', area: 'Email', offset: 0, limit: 100 }))
    await waitFor(() => expect(messages()).toEqual(['Outgoing email refused: authentication failed Resolved']))

    await userEvent.click(screen.getByRole('checkbox', { name: 'Unresolved Only' }))
    await waitFor(() => expect(router.state.location.search).toEqual({ area: 'Email', unresolved: 'true' }))
    await waitFor(() => expect(queries(api).at(-1)).toEqual({ window: '24h', area: 'Email', unresolved: true, offset: 0, limit: 100 }))
    expect(await screen.findByText('No errors match these filters.')).toBeTruthy()

    await userEvent.click(screen.getByRole('button', { name: 'Clear Filters' }))
    await waitFor(() => expect(router.state.location.search).toEqual({}))
    await waitFor(() => expect(messages()).toHaveLength(errorRows.length))
    expect((screen.getByRole('checkbox', { name: 'Unresolved Only' }) as HTMLInputElement).checked).toBe(false)
  })

  it('offers every area by its name, as the server names them', async () => {
    await openErrors()
    const options = within(screen.getByRole('combobox', { name: 'Area' })).getAllByRole('option')
    expect(options.map((one) => one.textContent)).toEqual(['Every Area', 'Previews', 'Storage', 'Search Index', 'Email', 'Sign-In', 'Background Jobs', 'Server'])
  })

  it('asks for the next 100 faults as the end of the list comes into view', async () => {
    const many = Array.from({ length: 150 }, (_, i) => errorRow({ id: 1000 + i, message: `Fault ${String(i + 1).padStart(3, '0')}`, times: 1 }))
    const { api } = await openErrors('/errors', { 'GET /v1/admin/errors': ({ query }: { query?: Record<string, unknown> }) => errorList(query, many) })
    act(() => bringObservedIntoView())
    expect(await within(table()).findByRole('rowheader', { name: 'Fault 150' })).toBeTruthy()
    expect(queries(api).map((query) => [query?.offset, query?.limit])).toEqual([
      [0, 100],
      [100, 100],
    ])
  })

  it('says there were none in a window with none, and none that match when filtered', async () => {
    const quiet = { 'GET /v1/admin/errors': ({ query }: { query?: Record<string, unknown> }) => (query?.window === '1h' ? errorList(query, []) : errorList(query)) }
    await renderApp('/errors?window=1h', { ...errorRoutes(), ...quiet })
    expect(await screen.findByText('No errors in the last hour.')).toBeTruthy()
    expect(screen.queryByText(/errors? of/)).toBeNull()
  })

  it("shows why the first read failed, with Try Again", async () => {
    let failing = true
    await renderApp('/errors', { ...errorRoutes(), 'GET /v1/admin/errors': ({ query }: { query?: Record<string, unknown> }) => (failing ? problem(500, 'server-error') : errorList(query)) })
    expect(await screen.findByText('Something went wrong.')).toBeTruthy()
    expect(screen.queryByRole('grid', { name: 'Errors' })).toBeNull()
    failing = false
    await userEvent.click(screen.getByRole('button', { name: 'Try Again' }))
    expect(await within(await screen.findByRole('grid', { name: 'Errors' })).findAllByRole('rowheader')).toHaveLength(errorRows.length)
  })

  it("keeps the list and the open fault's details while a new read fails, saying it couldn't refresh, with Try Again (P4-R15)", async () => {
    let failing = false
    const routes = errorRoutes()
    const fails = (route: string) => (request: { params: string[]; query?: Record<string, unknown> }) => (failing ? problem(500, 'server-error') : (routes[route] as (r: typeof request) => unknown)(request))
    await openErrors('/errors?id=12', { 'GET /v1/admin/errors': fails('GET /v1/admin/errors'), 'GET /v1/admin/errors/*': fails('GET /v1/admin/errors/*') })
    expect(await within(details()).findByText('Witness Statement.docx', { exact: false })).toBeTruthy()
    failing = true
    await act(async () => void window.dispatchEvent(new Event('focus')))
    expect(await screen.findByText("Couldn't refresh this, so it shows what was read last.")).toBeTruthy()
    expect(messages()).toHaveLength(errorRows.length)
    expect(within(details()).getByText('Witness Statement.docx', { exact: false })).toBeTruthy()
    failing = false
    await userEvent.click(screen.getByRole('button', { name: 'Try Again' }))
    await waitFor(() => expect(screen.queryByText("Couldn't refresh this, so it shows what was read last.")).toBeNull())
  })
})

describe("A fault's details", () => {
  it('open in a region below the list, not inside the row, with the open fault in the address', async () => {
    const { router, view } = await openErrors()
    expect(screen.queryByRole('region', { name: 'Error Details' })).toBeNull()
    await userEvent.click(within(table()).getByRole('rowheader', { name: /^Preview conversion/ }))
    await waitFor(() => expect(router.state.location.search).toEqual({ id: '12' }))
    const region = await screen.findByRole('region', { name: 'Error Details' })
    // Outside the list, whose rows keep their height, and after it
    expect(table().contains(region)).toBe(false)
    expect(table().compareDocumentPosition(region) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    // A scroll box the keyboard reaches, named apart from every other region
    expect(region.getAttribute('tabindex')).toBe('0')
    expect(screen.getAllByRole('region', { name: 'Error Details' })).toHaveLength(1)

    // The latest message and the stack, as text in boxes of their own
    const message = await within(region).findByRole('group', { name: 'Latest Message' })
    expect(message.textContent).toBe('Preview conversion timed out after 180 seconds, doc 10412 version 3')
    expect(message.tagName).toBe('PRE')
    const stack = within(region).getByRole('group', { name: 'Stack' })
    expect(stack.tagName).toBe('PRE')
    expect(stack.textContent).toBe(errorDetails[12]!.stack)
    // The facts: the level, the logger, how often, when, and whether anyone resolved it
    const fact = (name: string) => within(region).getByText(name, { selector: 'dt' }).nextElementSibling?.textContent
    expect(fact('Level')).toBe('Error')
    expect(fact('Area')).toBe('Previews')
    expect(fact('Logger')).toBe('com.logicaldoc.healdan.preview.PreviewWorker')
    expect(fact('Seen')).toBe('31 times in all')
    expect(fact('First Seen')).toBe(formatDateTime('2026-11-18T14:02:00Z'))
    expect(fact('Last Seen')).toBe(formatDateTime('2026-11-19T10:41:00Z'))
    expect(fact('Status')).toBe('Unresolved')
    // The documents it names: by doc # and file name, by file name and id with no doc #, and by id once gone
    expect(within(within(region).getByRole('list', { name: 'Documents Named' })).getAllByRole('listitem').map((item) => item.textContent)).toEqual([
      '# 10412 Witness Statement.docx',
      'Board Minutes.xlsx (id 5013)',
      'Id 4999, no longer there',
    ])
    await expectNoAxeViolationsInBothThemes(view.container)
  })

  it('opens the fault the address names, as a reload or a link does, and says who resolved one and when', async () => {
    await openErrors('/errors?id=15')
    const region = await screen.findByRole('region', { name: 'Error Details' })
    await within(region).findByRole('group', { name: 'Latest Message' })
    expect(within(region).getByText('Status', { selector: 'dt' }).nextElementSibling?.textContent).toBe(`Resolved by sam, ${formatDateTime('2026-11-19T09:00:00Z')}`)
    expect(within(region).getByText('It was logged with no stack.')).toBeTruthy()
    expect(within(region).getByText('Level', { selector: 'dt' }).nextElementSibling?.textContent).toBe('Error')
    expect(within(region).queryByRole('list', { name: 'Documents Named' })).toBeNull()
    expect(within(region).getByText('Documents Named', { selector: 'dt' }).nextElementSibling?.textContent).toBe('None')
    // A resolved fault offers Reopen
    expect(within(region).getByRole('button', { name: 'Reopen' })).toBeTruthy()
    expect(within(region).queryByRole('button', { name: 'Mark Resolved' })).toBeNull()
  })

  it('closes with Close Details, which takes the fault out of the address and gives the focus back to its row', async () => {
    const { router } = await openErrors('/errors?id=12&window=7d')
    const region = await screen.findByRole('region', { name: 'Error Details' })
    await userEvent.click(within(region).getByRole('button', { name: 'Close Details' }))
    await waitFor(() => expect(router.state.location.search).toEqual({ window: '7d' }))
    expect(screen.queryByRole('region', { name: 'Error Details' })).toBeNull()
    await waitFor(() => expect(document.activeElement?.closest('[data-row-key]')?.getAttribute('data-row-key')).toBe('12'))
    // And it stays closed
    await act(() => new Promise((resolve) => setTimeout(resolve, 50)))
    expect(router.state.location.search).toEqual({ window: '7d' })
    expect(screen.queryByRole('region', { name: 'Error Details' })).toBeNull()
  })

  it('takes the focus into the details when a row is opened by Enter, for the keyboard to read and scroll', async () => {
    const { router } = await openErrors()
    const row = within(table()).getAllByRole('row')[2]!
    row.focus()
    await userEvent.keyboard('{Enter}')
    await waitFor(() => expect(router.state.location.search).toEqual({ id: '13' }))
    const region = await screen.findByRole('region', { name: 'Error Details' })
    await waitFor(() => expect(document.activeElement).toBe(region))
  })

  it('says so when the fault the address names is there no more', async () => {
    await openErrors('/errors?id=99')
    const region = await screen.findByRole('region', { name: 'Error Details' })
    expect(await within(region).findByText("This error isn't there any more: the server deletes a resolved error once it hasn't been seen for 90 days.")).toBeTruthy()
  })

  it("shows what the server logged as text, never as markup: a fault's message, stack and documents come from log lines", async () => {
    const hostile = '<img src=x onerror="alert(1)"><b>bold</b>'
    const detail = errorDetailOf(errorRow({ message: `Failed ${hostile}` }), { stack: `at ${hostile}`, documents: [{ id: 7, docNumber: 7, fileName: hostile }] })
    const { view } = await openErrors('/errors?id=12', {
      'GET /v1/admin/errors': errorList(undefined, [errorRow({ message: `Failed ${hostile}` })]),
      'GET /v1/admin/errors/*': detail,
    })
    const region = await screen.findByRole('region', { name: 'Error Details' })
    await within(region).findByRole('group', { name: 'Latest Message' })
    expect(view.container.querySelector('img')).toBeNull()
    expect(view.container.querySelector('b')).toBeNull()
    expect(within(region).getByRole('group', { name: 'Latest Message' }).textContent).toBe(`Failed ${hostile}`)
    expect(within(region).getByRole('group', { name: 'Stack' }).textContent).toBe(`at ${hostile}`)
    expect(within(region).getByRole('listitem').textContent).toBe(`# 7 ${hostile}`)
    expect(messages()).toEqual([`Failed ${hostile}`])
  })
})

describe('Mark Resolved and Reopen', () => {
  it("resolve the fault, and the list and the left pane's count follow; the focus stays on the button, which then reads Reopen", async () => {
    const { api } = await openErrors('/errors?id=12')
    const region = await screen.findByRole('region', { name: 'Error Details' })
    const resolve = await within(region).findByRole('button', { name: 'Mark Resolved' })
    const dashboardReads = api.callsTo('GET /v1/admin/dashboard').length
    const listReads = queries(api).length
    resolve.focus()
    await userEvent.keyboard('{Enter}')
    await waitFor(() => expect(api.callsTo('POST /v1/admin/errors/12/resolve')).toHaveLength(1))
    const reopen = await within(region).findByRole('button', { name: 'Reopen' })
    expect(document.activeElement).toBe(reopen)
    expect(within(region).getByText('Status', { selector: 'dt' }).nextElementSibling?.textContent).toBe(`Resolved by admin, ${formatDateTime('2026-11-19T10:45:00Z')}`)
    // The list is asked again, and the left pane's count with the Dashboard's answer (P5-R20)
    await waitFor(() => expect(queries(api).length).toBeGreaterThan(listReads))
    await waitFor(() => expect(api.callsTo('GET /v1/admin/dashboard').length).toBeGreaterThan(dashboardReads))
    await waitFor(() => expect(messages()[0]).toBe('Preview conversion timed out after 180 seconds, doc 10412 version 3 Resolved'))

    const dashboardAgain = api.callsTo('GET /v1/admin/dashboard').length
    await userEvent.click(reopen)
    await waitFor(() => expect(api.callsTo('POST /v1/admin/errors/12/reopen')).toHaveLength(1))
    expect(await within(region).findByRole('button', { name: 'Mark Resolved' })).toBe(document.activeElement)
    expect(within(region).getByText('Status', { selector: 'dt' }).nextElementSibling?.textContent).toBe('Unresolved')
    await waitFor(() => expect(api.callsTo('GET /v1/admin/dashboard').length).toBeGreaterThan(dashboardAgain))
    await waitFor(() => expect(messages()[0]).toBe('Preview conversion timed out after 180 seconds, doc 10412 version 3'))
  })

  it('holds while the change is on its way, so a second press sends nothing more', async () => {
    const slow = slowAnswer<A['ErrorDetail']>()
    const { api } = await openErrors('/errors?id=12', { 'POST /v1/admin/errors/*/resolve': () => slow.answered })
    const region = await screen.findByRole('region', { name: 'Error Details' })
    const resolve = await within(region).findByRole('button', { name: 'Mark Resolved' })
    await userEvent.click(resolve)
    await waitFor(() => expect(api.callsTo('POST /v1/admin/errors/12/resolve')).toHaveLength(1))
    expect(resolve.getAttribute('data-pending')).toBe('true')
    await userEvent.click(resolve)
    expect(api.callsTo('POST /v1/admin/errors/12/resolve')).toHaveLength(1)
    await act(async () => slow.answer({ ...errorDetails[12]!, resolved: true, resolvedBy: 'admin', resolvedAt: '2026-11-19T10:45:00Z' }))
    expect(await within(region).findByRole('button', { name: 'Reopen' })).toBeTruthy()
  })

  it("says a refusal in the rule's words, and leaves the fault as it was", async () => {
    await openErrors('/errors?id=12', { 'POST /v1/admin/errors/*/resolve': problem(409, 'refused', 'Only an administrator can mark an error resolved.') })
    const region = await screen.findByRole('region', { name: 'Error Details' })
    await userEvent.click(await within(region).findByRole('button', { name: 'Mark Resolved' }))
    expect((await within(region).findByRole('alert')).textContent).toBe('Only an administrator can mark an error resolved.')
    expect(within(region).getByRole('button', { name: 'Mark Resolved' })).toBeTruthy()
    expect(within(region).getByText('Status', { selector: 'dt' }).nextElementSibling?.textContent).toBe('Unresolved')
  })
})

describe('Show in Server Log', () => {
  it("opens the Server Log on the fault's log, with the text filter set from its message's first line", async () => {
    const { router } = await openErrors('/errors?id=13')
    const region = await screen.findByRole('region', { name: 'Error Details' })
    const link = await within(region).findByRole('link', { name: 'Show in Server Log' })
    expect(link.getAttribute('href')).toBe('/admin/server-log?log=DMS&q=Upload+to+Wasabi+EU+failed%3A+connection+reset')
    await userEvent.click(link)
    await waitFor(() => expect(router.state.location.pathname).toBe('/server-log'))
    expect(router.state.location.search).toEqual({ log: 'DMS', q: 'Upload to Wasabi EU failed: connection reset' })
  })

  it("takes a task's own log, and cuts a long first line to what the text filter holds", async () => {
    const long = `Couldn't read PDF text ${'x'.repeat(200)}`
    await openErrors('/errors?id=14', { 'GET /v1/admin/errors/*': { ...errorDetails[14]!, message: long } })
    const link = await within(await screen.findByRole('region', { name: 'Error Details' })).findByRole('link', { name: 'Show in Server Log' })
    const search = new URL(link.getAttribute('href')!, 'http://localhost').searchParams
    expect(search.get('log')).toBe('IndexerTask')
    expect(search.get('q')).toBe(long.slice(0, 100))
  })

  it('is left out when no log listed holds its lines', async () => {
    await openErrors('/errors?id=15')
    const region = await screen.findByRole('region', { name: 'Error Details' })
    await within(region).findByRole('group', { name: 'Latest Message' })
    expect(within(region).queryByRole('link', { name: 'Show in Server Log' })).toBeNull()
  })
})

describe('Copy Details', () => {
  it('copies the message, the facts and the stack as plain text, and says it did', async () => {
    const writeText = vi.fn(() => Promise.resolve())
    vi.stubGlobal('navigator', { ...navigator, clipboard: { writeText } })
    try {
      await openErrors('/errors?id=12')
      const region = await screen.findByRole('region', { name: 'Error Details' })
      await userEvent.click(await within(region).findByRole('button', { name: 'Copy Details' }))
      expect(writeText).toHaveBeenCalledTimes(1)
      expect(writeText.mock.calls[0]).toEqual([
        [
          'Preview conversion timed out after 180 seconds, doc 10412 version 3',
          '',
          'Level: Error',
          'Area: Previews',
          'Logger: com.logicaldoc.healdan.preview.PreviewWorker',
          'Seen: 31 times in all',
          `First Seen: ${formatDateTime('2026-11-18T14:02:00Z')}`,
          `Last Seen: ${formatDateTime('2026-11-19T10:41:00Z')}`,
          'Status: Unresolved',
          'Documents Named: # 10412 Witness Statement.docx; Board Minutes.xlsx (id 5013); Id 4999, no longer there',
          '',
          'Stack:',
          errorDetails[12]!.stack,
        ].join('\n'),
      ])
      expect(await screen.findByText("Copied the error's details")).toBeTruthy()
    } finally {
      vi.unstubAllGlobals()
    }
  })

  it('says so on the page when the browser refuses the clipboard', async () => {
    vi.stubGlobal('navigator', { ...navigator, clipboard: { writeText: () => Promise.reject(new Error('Not allowed')) } })
    try {
      await openErrors('/errors?id=12')
      const region = await screen.findByRole('region', { name: 'Error Details' })
      await userEvent.click(await within(region).findByRole('button', { name: 'Copy Details' }))
      expect((await within(region).findByRole('alert')).textContent).toBe("The browser didn't let Healdan copy the details. Select them and copy them yourself.")
    } finally {
      vi.unstubAllGlobals()
    }
  })
})
