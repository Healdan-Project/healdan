// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { formatDate } from '@healdan/ui'
import { comeBack, settle } from '../test/comeBack'
import { detailsRoutes } from '../test/details'
import { document, page } from '../test/fakeApi'
import { renderApp } from '../test/renderApp'

const main = () => screen.getByRole('main')
const grid = async () => within(main()).findByRole('grid')
const rows = async () => {
  const found = await grid()
  await waitFor(() => expect(within(found).queryByText('Loading…')).toBeNull())
  return within(found).getAllByRole('row').slice(1)
}

const onMac = document({
  docNumber: 10431,
  name: 'Budget.xlsx',
  lastModified: '2026-11-14T11:30:00Z',
  hold: { kind: 'checkout', by: { id: 1, name: 'Priya Nair' }, since: '2026-11-16T09:00:00Z', device: { id: 'mac-1', name: 'Healdan Helper on MacBook', app: 'Excel' }, heldOn: { name: 'Healdan Helper on MacBook', app: 'Excel' } },
})
const inBrowser = document({
  docNumber: 10388,
  name: 'Letter.pdf',
  hold: { kind: 'checkout', by: { id: 1, name: 'Priya Nair' }, since: '2026-11-15T09:00:00Z', device: null, heldOn: null },
})
const two = { 'GET /v1/me/checkouts': page([onMac, inBrowser]) }

describe('Checked Out to Me', () => {
  // The list stands in for push updates, as the other lists that show a hold do; the left pane's count, on every page,
  // waits out its 30 seconds, as each read of the list reads every check-out on the server
  it('asks again at every return to the window, but the left pane alone waits out its 30 seconds', async () => {
    const { api } = await renderApp('/folders/1', two)
    const entry = await within(screen.getByRole('navigation', { name: 'Navigation' })).findByRole('link', { name: 'Checked Out to Me, 2 documents' })
    const asked = () => api.callsTo('GET /v1/me/checkouts').length
    const before = asked()
    comeBack()
    await settle()
    expect(asked()).toBe(before)

    await userEvent.click(entry)
    expect(await rows()).toHaveLength(2)
    const shown = asked()
    comeBack()
    await waitFor(() => expect(asked()).toBe(shown + 1))
  })

  it("lists your check-outs in the server's order", async () => {
    await renderApp('/checked-out', two)
    expect(await within(main()).findByRole('heading', { level: 1, name: 'Checked Out to Me' })).toBeTruthy()
    const found = await grid()
    const headers = within(found).getAllByRole('columnheader')
    expect(headers.map((h) => h.textContent)).toEqual(['Title', 'Doc #', 'Version', 'Matter', 'Computer', 'Checked Out', 'Edit Date'])
    expect(headers.every((h) => h.getAttribute('aria-sort') === null)).toBe(true)
    const list = await rows()
    const computer = (row: HTMLElement) => within(row).getAllByRole('gridcell')[3]!
    expect(within(computer(list[0]!)).getByText('MacBook', { exact: true })).toBeTruthy()
    expect(within(computer(list[0]!)).getByText('Excel', { exact: true })).toBeTruthy()
    expect(within(computer(list[1]!)).getByText('Web Page', { exact: true })).toBeTruthy()
  })

  it('shows the computer and its app on one line', async () => {
    await renderApp('/checked-out', two)
    const [mac, web] = (await rows()).map((row) => within(row).getAllByRole('gridcell')[3]!)
    // A dot between them, which only shows; the app keeps an element of its own
    expect(mac!.textContent).toBe('MacBook · Excel')
    const dot = within(mac!).getByText('·')
    expect(dot.getAttribute('aria-hidden')).toBe('true')
    // The web page's check-out has no app, so no dot
    expect(web!.textContent).toBe('Web Page')
    // The whole text is the tooltip, so an app the ellipsis cuts short can be read by pointing at it
    expect(within(mac!).getByTitle('MacBook · Excel', { exact: true }).textContent).toBe('MacBook · Excel')
    expect(within(web!).getByTitle('Web Page', { exact: true }).textContent).toBe('Web Page')
  })

  it('shows when each was checked out and last saved, as the lists show dates', async () => {
    await renderApp('/checked-out', two)
    const cells = (row: HTMLElement) => within(row).getAllByRole('gridcell').slice(4).map((cell) => cell.textContent)
    const [mac, web] = await rows()
    expect(cells(mac!)).toEqual([formatDate(onMac.hold!.since!), formatDate(onMac.lastModified)])
    expect(cells(web!)).toEqual([formatDate(inBrowser.hold!.since!), formatDate(inBrowser.lastModified)])
    // Not the same instant, so neither column shows the other's
    expect(formatDate(onMac.hold!.since!)).not.toBe(formatDate(onMac.lastModified))
  })

  it('says so when there are none', async () => {
    await renderApp('/checked-out')
    expect(await screen.findByText('Nothing is checked out to you.')).toBeTruthy()
  })

  it("shows the selected document's details", async () => {
    const { router } = await renderApp('/checked-out', { ...two, ...detailsRoutes(inBrowser) })
    const row = (await rows())[1]!
    await userEvent.click(within(row).getByRole('rowheader'))
    await waitFor(() => expect(router.history.location.search).toBe('?sel=10388'))
    const region = await screen.findByRole('region', { name: 'Details' })
    expect(await within(region).findByRole('heading', { name: 'Letter.pdf' })).toBeTruthy()
  })
})
