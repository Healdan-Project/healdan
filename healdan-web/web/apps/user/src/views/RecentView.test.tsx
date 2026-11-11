// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { expectNoAxeViolations } from '../test/axe'
import { document, folder, page, problem } from '../test/fakeApi'
import { detailsRoutes, letter } from '../test/details'
import { renderApp } from '../test/renderApp'

const main = () => screen.getByRole('main')
const grid = async () => within(main()).findByRole('grid')
// The list shows its header while the rows are on their way
const rows = async () => {
  const found = await grid()
  await waitFor(() => expect(within(found).queryByText('Loading…')).toBeNull())
  return within(found).getAllByRole('row').slice(1)
}

describe('Recent Documents', () => {
  it('is the start page: a list in the order the server gives (newest first), with the Matter column', async () => {
    const { api } = await renderApp('/')
    expect(await within(main()).findByRole('heading', { level: 1, name: 'Recent Documents' })).toBeTruthy()
    const found = await grid()
    expect(within(found).getAllByRole('columnheader').map((h) => h.textContent)).toEqual(['Title', 'Doc #', 'Version', 'Matter', 'Author', 'Edit Date'])
    const names = (await rows()).map((row) => within(row).getByRole('rowheader').textContent)
    expect(names).toEqual(['Letter to Dunmere Pryce.docx', 'Letter from Dunmere Pryce.pdf'])
    // The server's order, so nothing marks a column as the sort and no header sorts
    expect(within(found).getAllByRole('columnheader').every((h) => h.getAttribute('aria-sort') === null)).toBe(true)
    expect(api.callsTo('GET /v1/documents/recent')).toHaveLength(1)
    expect(api.callsTo('GET /v1/documents/recent')[0]?.query).toBeUndefined()
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
    fireEvent.keyDown(row!, { key: 'Enter' })
    fireEvent.keyUp(row!, { key: 'Enter' })
    await waitFor(() => expect(router.history.location.pathname + router.history.location.search).toBe('/app/search?scope=clients&q=1001'))
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
