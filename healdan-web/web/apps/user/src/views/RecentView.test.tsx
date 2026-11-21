// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { act, fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { expectNoAxeViolations } from '../test/axe'
import { sliderOf } from '../test/columns'
import { comeBack } from '../test/comeBack'
import { document, folder, page, problem, type Routes } from '../test/fakeApi'
import { detailsRoutes, letter } from '../test/details'
import { resetPdf } from '../test/fakePdf'
import { pdfResponse } from '../test/renderPreview'
import { renderApp } from '../test/renderApp'

vi.mock('../preview/pdfEngine', () => import('../test/fakePdf'))

beforeEach(resetPdf)

const main = () => screen.getByRole('main')
const grid = async () => within(main()).findByRole('grid')
// The list shows its header while the rows are on their way
const rows = async () => {
  const found = await grid()
  await waitFor(() => expect(within(found).queryByText('Loading…')).toBeNull())
  return within(found).getAllByRole('row').slice(1)
}
const titles = async () => (await rows()).map((row) => within(row).getByRole('rowheader').textContent)
const headers = () => within(within(main()).getByRole('grid')).getAllByRole('columnheader')
const headerNames = () => headers().map((h) => h.textContent)
const header = (name: string) => within(within(main()).getByRole('grid')).getByRole('columnheader', { name: new RegExp(`^${name}`) })
/** Each heading's aria-sort, by its name: React Aria's `none` on a heading that sorts and isn't the sort */
const sorts = () => Object.fromEntries(headers().map((h) => [h.textContent, h.getAttribute('aria-sort')]))

// The server's order is the newest used first; their titles and numbers run otherwise
const used = [
  document({ docNumber: 10500, name: 'Charlie.xlsx', lastModified: '2026-10-01T09:00:00Z', lastUsed: '2026-11-11T10:00:00Z' }),
  document({ docNumber: 10388, name: 'Alpha.pdf', lastModified: '2026-11-01T09:00:00Z', lastUsed: '2026-11-10T10:00:00Z' }),
  document({ docNumber: 10431, name: 'bravo.docx', lastModified: '2026-09-01T09:00:00Z', lastUsed: '2026-11-09T10:00:00Z' }),
]
const usedRoutes: Routes = { 'GET /v1/documents/recent': page(used) }

/** Opens a heading's menu by a right-click on it */
async function headingMenu(name: string) {
  expect(fireEvent.contextMenu(header(name))).toBe(false)
  return screen.findByRole('menu', { name: `${name} Column` })
}

describe('Recent Documents', () => {
  it('is the start page, with the Matter column', async () => {
    const { api } = await renderApp('/')
    expect(await within(main()).findByRole('heading', { level: 1, name: 'Recent Documents' })).toBeTruthy()
    expect(await titles()).toEqual(['Letter to Dunmere Pryce.docx', 'Letter from Dunmere Pryce.pdf'])
    expect(headerNames()).toContain('Matter')
    expect(api.callsTo('GET /v1/documents/recent')).toHaveLength(1)
    expect(api.callsTo('GET /v1/documents/recent')[0]?.query).toBeUndefined()
  })

  it('Recent Documents shows Last Used and starts sorted by it, newest first', async () => {
    const { router } = await renderApp('/', usedRoutes)
    expect(await titles()).toEqual(['Charlie.xlsx', 'Alpha.pdf', 'bravo.docx'])
    expect(headerNames()).toEqual(['Title', 'Doc #', 'Version', 'Matter', 'Author', 'Edit Date', 'Last Used'])
    // Its arrow shows, and every other column sorts too
    expect(sorts()).toEqual({ Title: 'none', 'Doc #': 'none', Version: 'none', Matter: 'none', Author: 'none', 'Edit Date': 'none', 'Last Used': 'descending' })
    // The list's own order needs nothing in the address
    expect(router.history.location.search).toBe('')
  })

  it('sorts by Title in the browser and comes back by Last Used', async () => {
    const { api, router } = await renderApp('/', usedRoutes)
    await titles()
    fireEvent.click(header('Title'))
    await waitFor(() => expect(router.history.location.search).toBe('?sort=title&order=asc'))
    // By file name, ignoring case
    await waitFor(async () => expect(await titles()).toEqual(['Alpha.pdf', 'bravo.docx', 'Charlie.xlsx']))
    expect(header('Title').getAttribute('aria-sort')).toBe('ascending')
    fireEvent.click(header('Title'))
    await waitFor(() => expect(router.history.location.search).toBe('?sort=title&order=desc'))
    expect(await titles()).toEqual(['Charlie.xlsx', 'bravo.docx', 'Alpha.pdf'])

    // A date's first click is newest first, which is the list's own order: the address loses the sort
    fireEvent.click(header('Last Used'))
    await waitFor(() => expect(router.history.location.search).toBe(''))
    expect(await titles()).toEqual(['Charlie.xlsx', 'Alpha.pdf', 'bravo.docx'])
    expect(header('Last Used').getAttribute('aria-sort')).toBe('descending')
    // Sorting in the browser asks the server nothing
    expect(api.callsTo('GET /v1/documents/recent')).toHaveLength(1)
    // Each sort replaced the address, so Back leaves the list
    expect(router.history.length).toBe(1)
  })

  it('sorts by Edit Date newest first at its first click, and back to its own order from the menu', async () => {
    const { router } = await renderApp('/recent/documents', usedRoutes)
    await titles()
    fireEvent.click(header('Edit Date'))
    await waitFor(() => expect(router.history.location.search).toBe('?sort=edited&order=desc'))
    expect(await titles()).toEqual(['Alpha.pdf', 'Charlie.xlsx', 'bravo.docx'])
    await userEvent.click(within(await headingMenu('Last Used')).getByRole('menuitemradio', { name: 'Sort Descending' }))
    await waitFor(() => expect(router.history.location.search).toBe(''))
    expect(await titles()).toEqual(['Charlie.xlsx', 'Alpha.pdf', 'bravo.docx'])
  })

  it('keeps a sort from the address, and ignores one the list has no column for', async () => {
    const { view } = await renderApp('/?sort=docNumber&order=desc', usedRoutes)
    expect(await titles()).toEqual(['Charlie.xlsx', 'bravo.docx', 'Alpha.pdf'])
    expect(header('Doc #').getAttribute('aria-sort')).toBe('descending')
    view.unmount()

    // Computer is Checked Out to Me's, and a client's name Recent Clients'
    for (const address of ['/?sort=computer&order=asc', '/recent/documents?sort=name&order=asc', '/?sort=bogus']) {
      const { view: another } = await renderApp(address, usedRoutes)
      expect(await titles()).toEqual(['Charlie.xlsx', 'Alpha.pdf', 'bravo.docx'])
      expect(header('Last Used').getAttribute('aria-sort')).toBe('descending')
      another.unmount()
    }
  })

  it('opened from the left pane, starts in its own order', async () => {
    const { router } = await renderApp('/recent/documents?sort=title&order=asc', usedRoutes)
    expect(await titles()).toEqual(['Alpha.pdf', 'bravo.docx', 'Charlie.xlsx'])
    await userEvent.click(within(screen.getByRole('navigation', { name: 'Navigation' })).getByRole('link', { name: 'Documents' }))
    await waitFor(() => expect(router.history.location.search).toBe(''))
    await waitFor(async () => expect(await titles()).toEqual(['Charlie.xlsx', 'Alpha.pdf', 'bravo.docx']))
    expect(header('Last Used').getAttribute('aria-sort')).toBe('descending')
  })

  it('keeps a sort while it asks again, at a return to the window', async () => {
    let answer = page(used)
    const { api, router } = await renderApp('/?sort=title&order=asc', { 'GET /v1/documents/recent': () => answer })
    expect(await titles()).toEqual(['Alpha.pdf', 'bravo.docx', 'Charlie.xlsx'])
    answer = page([document({ docNumber: 10600, name: 'Delta.pdf', lastUsed: '2026-11-12T10:00:00Z' }), ...used])
    comeBack()
    await waitFor(() => expect(api.callsTo('GET /v1/documents/recent')).toHaveLength(2))
    await waitFor(async () => expect(await titles()).toEqual(['Alpha.pdf', 'bravo.docx', 'Charlie.xlsx', 'Delta.pdf']))
    expect(router.history.location.search).toBe('?sort=title&order=asc')
  })

  it('hides a column from its heading menu, leaving the focus in the list', async () => {
    await renderApp('/', usedRoutes)
    await titles()
    await userEvent.click(within(await headingMenu('Author')).getByRole('menuitem', { name: 'Hide Author' }))
    await waitFor(() => expect(headerNames()).toEqual(['Title', 'Doc #', 'Version', 'Matter', 'Edit Date', 'Last Used']))
    // Not left on the page's body, with the heading it was on gone
    await waitFor(() => expect(within(main()).getByRole('grid').contains(globalThis.document.activeElement)).toBe(true))
    // Kept for Recent Documents, so Recent Emails still has its Author
    expect(JSON.parse(window.localStorage.getItem('healdan.columns.recentDocuments')!).shown).not.toContain('author')
    expect(window.localStorage.getItem('healdan.columns.recentEmails')).toBeNull()
  })

  it('chooses its columns in the Columns panel, for Recent Documents', async () => {
    await renderApp('/', usedRoutes)
    await titles()
    const list = within(main()).getByRole('grid')
    await userEvent.click(within(main()).getByRole('button', { name: 'Columns' }))
    const dialog = await screen.findByRole('dialog', { name: 'Columns', description: 'For Recent Documents' })
    // Every document column, and Last Used; the list behind changes at once
    expect(within(dialog).getByRole('checkbox', { name: 'Last Used' })).toBeTruthy()
    await userEvent.click(within(dialog).getByRole('checkbox', { name: 'Size' }))
    await waitFor(() =>
      expect(within(list).getAllByRole('columnheader', { hidden: true }).map((h) => h.textContent)).toEqual(['Title', 'Doc #', 'Version', 'Matter', 'Author', 'Edit Date', 'Last Used', 'Size']),
    )
    expect(within(dialog).queryByRole('checkbox', { name: 'Computer' })).toBeNull()
  })

  it('a refetch while the Columns panel is open keeps it open and its focus', async () => {
    let release: (value: unknown) => void = () => {}
    let calls = 0
    const { api } = await renderApp('/', {
      'GET /v1/documents/recent': () => {
        calls += 1
        return calls === 1 ? page(used) : new Promise((resolve) => (release = resolve))
      },
    })
    await titles()
    const list = within(main()).getByRole('grid')
    await userEvent.click(within(main()).getByRole('button', { name: 'Columns' }))
    const dialog = await screen.findByRole('dialog', { name: 'Columns' })
    const box = within(dialog).getByRole('checkbox', { name: 'Version' })
    act(() => box.focus())
    expect(globalThis.document.activeElement).toBe(box)

    comeBack()
    await waitFor(() => expect(api.callsTo('GET /v1/documents/recent')).toHaveLength(2))
    act(() => release(page([document({ docNumber: 10600, name: 'Delta.pdf', lastUsed: '2026-11-12T10:00:00Z' }), ...used])))
    // The list behind the panel has the new rows
    await waitFor(() =>
      expect(within(list).getAllByRole('rowheader', { hidden: true }).map((cell) => cell.textContent)).toEqual(['Delta.pdf', 'Charlie.xlsx', 'Alpha.pdf', 'bravo.docx']),
    )
    expect(screen.getByRole('dialog', { name: 'Columns' })).toBe(dialog)
    expect(within(dialog).getByRole('checkbox', { name: 'Version' })).toBe(box)
    expect(globalThis.document.activeElement).toBe(box)
  })

  it("the reading pane's list uses its own layout", async () => {
    const docs = [{ ...letter, lastUsed: '2026-11-10T10:00:00Z' }, used[2]!]
    await renderApp('/?sel=10388', { 'GET /v1/documents/recent': page(docs), 'GET /v1/documents/*/preview': () => pdfResponse(), ...detailsRoutes(letter) })
    await titles()
    const details = await screen.findByRole('region', { name: 'Details' })
    fireEvent.click(await within(details).findByRole('button', { name: 'Preview' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Open as Reading Pane' }))
    const pane = await screen.findByRole('region', { name: 'Preview' })
    await waitFor(() => expect(headerNames()).toEqual(['Title', 'Doc #', 'Edit Date']))

    // Beside the pane, the panel says it's for every list there
    await userEvent.click(within(main()).getByRole('button', { name: 'Columns' }))
    expect(await screen.findByRole('dialog', { name: 'Columns', description: 'For Lists Beside the Reading Pane' })).toBeTruthy()
    await userEvent.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Columns' })).toBeNull())

    await userEvent.click(within(await headingMenu('Doc #')).getByRole('menuitem', { name: 'Hide Doc #' }))
    await waitFor(() => expect(headerNames()).toEqual(['Title', 'Edit Date']))

    fireEvent.click(within(pane).getByRole('button', { name: 'Close Preview' }))
    await waitFor(() => expect(headerNames()).toEqual(['Title', 'Doc #', 'Version', 'Matter', 'Author', 'Edit Date', 'Last Used']))
    expect(window.localStorage.getItem('healdan.columns.recentDocuments')).toBeNull()
    expect(JSON.parse(window.localStorage.getItem('healdan.columns.beside')!).shown).toEqual(['title', 'edited'])
  })

  it('is also at /recent/documents, and sets the scope to Documents', async () => {
    await renderApp('/recent/documents')
    expect(await grid()).toBeTruthy()
    expect(screen.getByRole('button', { name: /Search In.*Documents/ })).toBeTruthy()
  })

  it('says so when there are none', async () => {
    await renderApp('/', { 'GET /v1/documents/recent': page([]) })
    expect(await screen.findByText('No recent documents.')).toBeTruthy()
  })

  it('puts the selected row in the address and shows its details, with Location', async () => {
    const { router } = await renderApp('/', detailsRoutes(letter))
    const row = (await rows())[1]!
    await userEvent.click(within(row).getByRole('rowheader'))
    await waitFor(() => expect(router.history.location.search).toBe('?sel=10388'))
    const region = await screen.findByRole('region', { name: 'Details' })
    expect(await within(region).findByRole('heading', { name: 'Letter from Dunmere Pryce.pdf' })).toBeTruthy()
    expect(await within(region).findByText('Location')).toBeTruthy()
  })

  it('reads the selected row from the address', async () => {
    await renderApp('/?sel=10431', detailsRoutes(document({ docNumber: 10431, name: 'Letter to Dunmere Pryce.docx' })))
    await waitFor(async () => expect((await rows())[0]!.getAttribute('aria-selected')).toBe('true'))
    expect(await screen.findByRole('region', { name: 'Details' })).toBeTruthy()
  })

  it('shows a failure with Try Again, which asks again', async () => {
    let fail = true
    const { api } = await renderApp('/', { 'GET /v1/documents/recent': () => (fail ? problem(500, 'server-error') : page([])) })
    const retry = await screen.findByRole('button', { name: 'Try Again' })
    fail = false
    fireEvent.click(retry)
    expect(await screen.findByText('No recent documents.')).toBeTruthy()
    expect(api.callsTo('GET /v1/documents/recent').length).toBe(2)
  })

  it('passes axe', async () => {
    const { view } = await renderApp('/')
    await rows()
    await expectNoAxeViolations(view.container)
  })
})

describe('Recent Emails', () => {
  it('lists the emails and sets the scope to Emails', async () => {
    const { api } = await renderApp('/recent/emails', {
      'GET /v1/documents/recent': ({ query }: { query?: Record<string, unknown> }) =>
        query?.kind === 'email' ? page([document({ docNumber: 10500, name: 'Re Claim.eml' })]) : page([]),
    })
    expect(await within(main()).findByRole('heading', { level: 1, name: 'Recent Emails' })).toBeTruthy()
    expect((await rows()).map((row) => within(row).getByRole('rowheader').textContent)).toEqual(['Re Claim.eml'])
    expect(api.callsTo('GET /v1/documents/recent')[0]?.query).toEqual({ kind: 'email' })
    expect(screen.getByRole('button', { name: /Search In.*Emails/ })).toBeTruthy()
  })

  it('shows Last Used and starts sorted by it, newest first, with a layout of its own', async () => {
    const { router } = await renderApp('/recent/emails', usedRoutes)
    expect(await titles()).toEqual(['Charlie.xlsx', 'Alpha.pdf', 'bravo.docx'])
    expect(headerNames()).toEqual(['Title', 'Doc #', 'Matter', 'Author', 'Edit Date', 'Last Used'])
    expect(header('Last Used').getAttribute('aria-sort')).toBe('descending')
    fireEvent.click(header('Doc #'))
    await waitFor(() => expect(router.history.location.search).toBe('?sort=docNumber&order=asc'))
    expect(await titles()).toEqual(['Alpha.pdf', 'bravo.docx', 'Charlie.xlsx'])
    await userEvent.click(within(main()).getByRole('button', { name: 'Columns' }))
    expect(await screen.findByRole('dialog', { name: 'Columns', description: 'For Recent Emails' })).toBeTruthy()
  })

  it('says so when there are none', async () => {
    await renderApp('/recent/emails', { 'GET /v1/documents/recent': page([]) })
    expect(await screen.findByText('No recent emails.')).toBeTruthy()
  })
})

describe('Recent Folders', () => {
  it('lists the folders with their matter, opens one, and sets the scope to Folders', async () => {
    const { router } = await renderApp('/recent/folders', { 'GET /v1/folders/recent': page([folder({ id: 111, name: 'Correspondence' })]) })
    expect(await within(main()).findByRole('heading', { level: 1, name: 'Recent Folders' })).toBeTruthy()
    expect(screen.getByRole('button', { name: /Search In.*Folders/ })).toBeTruthy()
    const [row] = await rows()
    expect(row!.textContent).toContain('Correspondence')
    expect(row!.textContent).toContain('1001.1')
    expect(row!.textContent).toContain('Charter Dispute')
    fireEvent.keyDown(row!, { key: 'Enter' })
    fireEvent.keyUp(row!, { key: 'Enter' })
    await waitFor(() => expect(router.history.location.pathname).toBe('/app/folders/111'))
  })

  it('Recent Folders shows Last Used and starts sorted by it, newest first', async () => {
    const correspondence = folder({ id: 111, name: 'Correspondence', lastUsed: '2026-11-11T10:00:00Z' })
    const pleadings = folder({ id: 112, name: 'pleadings', lastUsed: '2026-11-10T10:00:00Z', matter: { id: 12, display: '1002.1 – Commercial Lease', client: { id: 2, number: 1002, name: 'Brightwell' } } })
    const agreements = folder({ id: 113, name: 'Agreements', lastUsed: '2026-11-09T10:00:00Z', matter: { id: 13, display: '1001.10 – Salvage', client: { id: 1, number: 1001, name: 'Saltmarsh Shipping Ltd' } } })
    const { api, router } = await renderApp('/recent/folders', { 'GET /v1/folders/recent': page([correspondence, pleadings, agreements]) })
    expect(await titles()).toEqual(['Correspondence', 'pleadings', 'Agreements'])
    expect(headerNames()).toEqual(['Title', 'Matter', 'Last Used'])
    expect(sorts()).toEqual({ Title: 'none', Matter: 'none', 'Last Used': 'descending' })
    // Its columns aren't chosen, so it has no Columns button, but they resize
    expect(within(main()).queryByRole('button', { name: 'Columns' })).toBeNull()
    expect(sliderOf(header('Title'))).toBeTruthy()

    fireEvent.click(header('Title'))
    await waitFor(() => expect(router.history.location.search).toBe('?sort=title&order=asc'))
    expect(await titles()).toEqual(['Agreements', 'Correspondence', 'pleadings'])
    // By client number, then matter number, as numbers
    fireEvent.click(header('Matter'))
    await waitFor(() => expect(router.history.location.search).toBe('?sort=matter&order=asc'))
    expect(await titles()).toEqual(['Correspondence', 'Agreements', 'pleadings'])
    fireEvent.click(header('Last Used'))
    await waitFor(() => expect(router.history.location.search).toBe(''))
    expect(await titles()).toEqual(['Correspondence', 'pleadings', 'Agreements'])
    expect(api.callsTo('GET /v1/folders/recent')).toHaveLength(1)
  })

  it('ignores a sort from the address that it has no column for', async () => {
    await renderApp('/recent/folders?sort=docNumber&order=asc')
    await titles()
    expect(header('Last Used').getAttribute('aria-sort')).toBe('descending')
  })

  it('says so when there are none', async () => {
    await renderApp('/recent/folders', { 'GET /v1/folders/recent': page([]) })
    expect(await screen.findByText('No recent folders.')).toBeTruthy()
  })
})

describe('Recent Clients', () => {
  it('lists the clients, and opening one searches that client\'s matters', async () => {
    const { router } = await renderApp('/recent/clients')
    expect(await within(main()).findByRole('heading', { level: 1, name: 'Recent Clients' })).toBeTruthy()
    expect(screen.getByRole('button', { name: /Search In.*Clients/ })).toBeTruthy()
    const [row] = await rows()
    expect(row!.textContent).toContain('Saltmarsh Shipping Ltd')
    expect(row!.textContent).toContain('1001')
    expect(row!.querySelector('.tabler-icon-building')!.getAttribute('data-kind')).toBe('client')
    fireEvent.keyDown(row!, { key: 'Enter' })
    fireEvent.keyUp(row!, { key: 'Enter' })
    await waitFor(() => expect(router.history.location.pathname + router.history.location.search).toBe('/app/search?scope=clients&q=1001'))
  })

  it('Recent Clients starts by Client, A to Z, and sorts by any column', async () => {
    const clients = [
      { number: 1001, name: 'Saltmarsh Shipping Ltd' },
      { number: 1003, name: 'brightwell & co' },
      { number: 990, name: 'Thornbury' },
    ]
    const { api, router } = await renderApp('/recent/clients', { 'GET /v1/clients/recent': clients })
    // Its name ignoring case
    expect(await titles()).toEqual(['brightwell & co', 'Saltmarsh Shipping Ltd', 'Thornbury'])
    expect(headerNames()).toEqual(['Client', 'Number'])
    expect(sorts()).toEqual({ Client: 'ascending', Number: 'none' })
    expect(within(main()).queryByRole('button', { name: 'Columns' })).toBeNull()
    expect(sliderOf(header('Client'))).toBeTruthy()

    // The number as a number
    fireEvent.click(header('Number'))
    await waitFor(() => expect(router.history.location.search).toBe('?sort=number&order=asc'))
    expect(await titles()).toEqual(['Thornbury', 'Saltmarsh Shipping Ltd', 'brightwell & co'])
    fireEvent.click(header('Number'))
    await waitFor(() => expect(router.history.location.search).toBe('?sort=number&order=desc'))
    expect(await titles()).toEqual(['brightwell & co', 'Saltmarsh Shipping Ltd', 'Thornbury'])
    fireEvent.click(header('Client'))
    await waitFor(() => expect(router.history.location.search).toBe(''))
    fireEvent.click(header('Client'))
    await waitFor(() => expect(router.history.location.search).toBe('?sort=name&order=desc'))
    expect(await titles()).toEqual(['Thornbury', 'Saltmarsh Shipping Ltd', 'brightwell & co'])
    expect(api.callsTo('GET /v1/clients/recent')).toHaveLength(1)
  })

  it('says so when there are none', async () => {
    await renderApp('/recent/clients', { 'GET /v1/clients/recent': [] })
    expect(await screen.findByText('No recent clients.')).toBeTruthy()
  })
})

describe('an unknown kind', () => {
  it('is not found', async () => {
    await renderApp('/recent/bogus')
    expect(await screen.findByText("Not found, or you can't see it.")).toBeTruthy()
  })
})
