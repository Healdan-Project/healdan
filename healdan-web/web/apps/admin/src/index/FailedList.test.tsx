// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { ApiError, type adminComponents } from '@healdan/api'
import { formatDateTime } from '@healdan/ui'
import { expectNoAxeViolations } from '../test/axe'
import { problem, searchIndexDocument, searchIndexFailures, searchIndexRoutes, type Routes, type SearchIndexRoutesOptions } from '../test/fakeApi'
import { renderApp } from '../test/renderApp'
import { slowAnswer } from '../test/slowAnswer'

type A = adminComponents['schemas']

// axe runs once for each state drawn; the themes' colours are tokens, which test/colours.test.ts checks in both themes.

const table = () => screen.getByRole('table', { name: "Couldn't Be Indexed" })
const row = (docNumber: string) => within(table()).getByRole('rowheader', { name: docNumber }).closest('tr')!
const cells = (docNumber: string) => within(row(docNumber)).getAllByRole('cell').map((cell) => cell.textContent)
const under = (docNumber: string) => row(docNumber).nextElementSibling as HTMLElement
const docNumbers = () => within(table()).queryAllByRole('rowheader').map((header) => header.textContent)

async function open(options: SearchIndexRoutesOptions = {}, over: Routes = {}) {
  const fake = searchIndexRoutes(options)
  const rendered = await renderApp('/search-index', { ...fake.routes, ...over })
  await within(await screen.findByRole('table', { name: "Couldn't Be Indexed" })).findAllByRole('rowheader')
  return { ...rendered, fake }
}

describe("Couldn't Be Indexed", () => {
  it("lists each document that couldn't be indexed, the latest error first, with its doc #, title, matter, reason and when, as plain text", async () => {
    const { view } = await open()
    expect(within(table()).getAllByRole('columnheader').map((heading) => heading.textContent)).toEqual(['Doc #', 'Title', 'Matter', 'Reason', 'When', 'Actions'])
    expect(docNumbers()).toEqual(['# 10412', '# 10398'])
    expect(cells('# 10412').slice(0, 4)).toEqual(['Witness Statement.pdf', '1001.1', 'The file is encrypted', formatDateTime('2026-11-19T08:31:00Z')])
    expect(within(row('# 10412')).queryAllByRole('link')).toEqual([])
    expect(within(row('# 10412')).getAllByRole('button').map((button) => button.getAttribute('aria-label'))).toEqual([
      'Try Again: # 10412 Witness Statement.pdf',
      'Skip # 10412 Witness Statement.pdf',
    ])
    await expectNoAxeViolations(view.container)
  })

  it("draws a reason as text, never markup, and says when none was kept", async () => {
    const hostile = '<img src=x onerror="alert(1)">'
    const { view } = await open({ failures: [{ document: searchIndexDocument(), reason: hostile, when: null }, { ...searchIndexFailures[1]!, reason: null }] })
    expect(cells('# 10412')[2]).toBe(hostile)
    expect(cells('# 10412')[3]).toBe('')
    expect(cells('# 10398')[2]).toBe('No reason was kept')
    expect(view.container.querySelector('img')).toBeNull()
  })

  it('greys out Try Again and Skip for a document with no doc #, saying why', async () => {
    const { view } = await open({ failures: [{ document: searchIndexDocument({ docNumber: null }), reason: 'The file is encrypted', when: null }] })
    const header = within(table()).getByRole('rowheader', { name: 'No doc #' })
    const buttons = within(header.closest('tr')!).getAllByRole('button')
    const why = screen.getByText("This document has no doc #, so it can't be tried again from here. Reindex its folder to try it again.")
    for (const button of buttons) {
      expect(button.hasAttribute('disabled')).toBe(true)
      expect(button.getAttribute('aria-describedby')).toBe(why.id)
    }
    await expectNoAxeViolations(view.container)
  })

  it('says so when nothing is there', async () => {
    const fake = searchIndexRoutes({ failures: [] })
    await renderApp('/search-index', fake.routes)
    expect(await screen.findByText('Every document has been indexed or is waiting to be.')).toBeTruthy()
  })

  it("keeps the rows while a new read fails, saying it couldn't refresh (P4-R15)", async () => {
    let failing = false
    const fake = searchIndexRoutes()
    const list = fake.routes['GET /v1/admin/search-index/failed']
    await renderApp('/search-index', { ...fake.routes, 'GET /v1/admin/search-index/failed': (request: { query?: Record<string, unknown> }) => (failing ? problem(500, 'server-error') : list(request)) })
    await within(await screen.findByRole('table', { name: "Couldn't Be Indexed" })).findAllByRole('rowheader')
    failing = true
    await act(async () => void window.dispatchEvent(new Event('focus')))
    expect(await screen.findAllByText("Couldn't refresh this, so it shows what was read last.")).not.toHaveLength(0)
    expect(docNumbers()).toEqual(['# 10412', '# 10398'])
  })

  it('loads more with Show More, a page at a time', async () => {
    const many: A['IndexFailure'][] = Array.from({ length: 130 }, (_, i) => ({ document: searchIndexDocument({ id: 6000 + i, docNumber: 20000 + i }), reason: 'The file is encrypted', when: null }))
    const { api } = await open({ failures: many })
    expect(docNumbers()).toHaveLength(100)
    await userEvent.click(screen.getByRole('button', { name: 'Show More' }))
    await waitFor(() => expect(docNumbers()).toHaveLength(130))
    expect(api.callsTo('GET /v1/admin/search-index/failed').at(-1)?.query).toEqual({ offset: 100, limit: 100 })
    expect(screen.queryByRole('button', { name: 'Show More' })).toBeNull()
  })
})

describe('Try Again', () => {
  it('by keyboard alone, indexes the document now, holding its button, says how it went, and reads the list, the counts and the Dashboard again; the focus goes to the row that comes to its place', async () => {
    const slow = slowAnswer<A['IndexRetry']>()
    const fake = searchIndexRoutes()
    const retry = fake.routes['POST /v1/admin/search-index/failed/*/retry']
    const { api } = await renderApp('/search-index', { ...fake.routes, 'POST /v1/admin/search-index/failed/*/retry': (request: { params: string[] }) => slow.answered.then(() => retry(request)) })
    await within(await screen.findByRole('table', { name: "Couldn't Be Indexed" })).findAllByRole('rowheader')
    const before = { dashboard: api.callsTo('GET /v1/admin/dashboard').length, index: api.callsTo('GET /v1/admin/search-index').length }
    const button = within(row('# 10412')).getByRole('button', { name: /^Try Again/ })
    button.focus()
    await userEvent.keyboard('{Enter}')
    expect(api.callsTo('POST /v1/admin/search-index/failed/10412/retry')).toHaveLength(1)
    expect(button.getAttribute('data-pending')).toBe('true')
    await userEvent.keyboard('{Enter}')
    expect(api.callsTo('POST /v1/admin/search-index/failed/10412/retry')).toHaveLength(1)
    await act(async () => slow.answer({ outcome: 'indexed', reason: null }))
    expect(await screen.findByText('Indexed # 10412 Witness Statement.pdf')).toBeTruthy()
    await waitFor(() => expect(docNumbers()).toEqual(['# 10398']))
    expect(await screen.findByRole('tab', { name: "Couldn't Be Indexed (1)" })).toBeTruthy()
    expect(api.callsTo('GET /v1/admin/dashboard').length).toBeGreaterThan(before.dashboard)
    expect(api.callsTo('GET /v1/admin/search-index').length).toBeGreaterThan(before.index)
    await waitFor(() => expect(document.activeElement).toBe(within(table()).getByRole('rowheader', { name: '# 10398' })))
  })

  it('says in the row when it still couldn\'t be indexed, with the new reason, and the row stays', async () => {
    const { view } = await open({ retry: () => ({ outcome: 'failed', reason: 'The file is damaged' }) })
    const button = within(row('# 10412')).getByRole('button', { name: /^Try Again/ })
    await userEvent.click(button)
    expect(await within(under('# 10412')).findByText("Still couldn't be indexed: The file is damaged")).toBeTruthy()
    expect(await screen.findByText("# 10412 Witness Statement.pdf still couldn't be indexed")).toBeTruthy()
    await waitFor(() => expect(cells('# 10412')[2]).toBe('The file is damaged'))
    expect(document.activeElement).toBe(button)
    await expectNoAxeViolations(view.container)
  })

  it("says a refusal in its row, in the rule's words, and leaves the row to say so", async () => {
    await open({}, { 'POST /v1/admin/search-index/failed/*/retry': problem(409, 'refused', 'This document is no longer waiting after an error.') })
    await userEvent.click(within(row('# 10412')).getByRole('button', { name: /^Try Again/ }))
    expect((await within(under('# 10412')).findByRole('alert')).textContent).toBe('This document is no longer waiting after an error.')
    expect(docNumbers()).toEqual(['# 10412', '# 10398'])
  })

  it("says in its row that the document's storage can't be reached, in the server's sentence, which names the Store", async () => {
    const unreachable = new ApiError(503, 'storage-unreachable', "Client Work's storage can't be reached right now.", {
      status: 503,
      code: 'storage-unreachable',
      detail: "Client Work's storage can't be reached right now.",
    })
    await open({}, { 'POST /v1/admin/search-index/failed/*/retry': unreachable })
    await userEvent.click(within(row('# 10412')).getByRole('button', { name: /^Try Again/ }))
    expect((await within(under('# 10412')).findByRole('alert')).textContent).toBe("Client Work's storage can't be reached right now.")
    expect(docNumbers()).toEqual(['# 10412', '# 10398'])
  })

  it("says the catalogue's words for any other failure, never the server's text", async () => {
    await open({}, { 'POST /v1/admin/search-index/failed/*/retry': problem(500, 'server-error') })
    await userEvent.click(within(row('# 10412')).getByRole('button', { name: /^Try Again/ }))
    const alert = await within(under('# 10412')).findByRole('alert')
    expect(alert.textContent).not.toContain('A server sentence')
  })
})

describe('Skip', () => {
  it('asks first, saying it won\'t be tried again, and Cancel sends nothing', async () => {
    const { api } = await open()
    await userEvent.click(within(row('# 10412')).getByRole('button', { name: /^Skip/ }))
    const question = await screen.findByRole('alertdialog', { name: 'Skip # 10412 Witness Statement.pdf?' })
    expect(within(question).getByText(/It won't be tried again/)).toBeTruthy()
    await expectNoAxeViolations(question)
    await userEvent.click(within(question).getByRole('button', { name: 'Cancel' }))
    await waitFor(() => expect(screen.queryByRole('alertdialog', { name: /^Skip/ })).toBeNull())
    expect(api.callsTo('POST /v1/admin/search-index/failed/*/skip')).toEqual([])
  })

  it('by keyboard alone, sets it not to be indexed, reads the list and the Dashboard again, and the focus goes to the row that comes to its place', async () => {
    const { api } = await open()
    const dashboard = api.callsTo('GET /v1/admin/dashboard').length
    within(row('# 10412')).getByRole('button', { name: /^Skip/ }).focus()
    await userEvent.keyboard('{Enter}')
    const question = await screen.findByRole('alertdialog', { name: 'Skip # 10412 Witness Statement.pdf?' })
    within(question).getByRole('button', { name: 'Cancel' }).focus()
    await userEvent.tab()
    expect(document.activeElement).toBe(within(question).getByRole('button', { name: 'Skip' }))
    await userEvent.keyboard('{Enter}')
    await waitFor(() => expect(screen.queryByRole('alertdialog', { name: /^Skip/ })).toBeNull())
    expect(api.callsTo('POST /v1/admin/search-index/failed/10412/skip')).toHaveLength(1)
    expect(await screen.findByText('Set # 10412 Witness Statement.pdf not to be indexed')).toBeTruthy()
    await waitFor(() => expect(docNumbers()).toEqual(['# 10398']))
    expect(api.callsTo('GET /v1/admin/dashboard').length).toBeGreaterThan(dashboard)
    await waitFor(() => expect(document.activeElement).toBe(within(table()).getByRole('rowheader', { name: '# 10398' })))
  })

  it('gives the focus to the line above the list once the last row goes', async () => {
    await open({ failures: [searchIndexFailures[0]!] })
    await userEvent.click(within(row('# 10412')).getByRole('button', { name: /^Skip/ }))
    await userEvent.click(within(await screen.findByRole('alertdialog', { name: /^Skip/ })).getByRole('button', { name: 'Skip' }))
    expect(await screen.findByText('Every document has been indexed or is waiting to be.')).toBeTruthy()
    await waitFor(() => expect(document.activeElement?.textContent).toMatch(/^Each document that couldn't be indexed/))
  })

  it("says a refusal in its row, once the question has closed", async () => {
    await open({}, { 'POST /v1/admin/search-index/failed/*/skip': problem(409, 'refused', 'This document is no longer waiting after an error.') })
    await userEvent.click(within(row('# 10412')).getByRole('button', { name: /^Skip/ }))
    await userEvent.click(within(await screen.findByRole('alertdialog', { name: /^Skip/ })).getByRole('button', { name: 'Skip' }))
    await waitFor(() => expect(screen.queryByRole('alertdialog', { name: /^Skip/ })).toBeNull())
    expect((await within(under('# 10412')).findByRole('alert')).textContent).toBe('This document is no longer waiting after an error.')
  })
})
