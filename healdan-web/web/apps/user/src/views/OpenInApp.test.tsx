// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { act, fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { launch } from '../actions/launch'
import { comeBack, settle } from '../test/comeBack'
import { detailsRoutes } from '../test/details'
import { children, document, folder, page, type Routes } from '../test/fakeApi'
import { renderApp } from '../test/renderApp'

// Double-click and Enter open a document in its app on every list of documents (spec 10.5 and 10.6); the helper's kinds go to
// the helper through its healdan: link, and the rest download.
const word = document({ docNumber: 10431, name: 'Letter to Dunmere Pryce.docx' })
const picture = document({ docNumber: 10500, name: 'Site Photo.png' })
const locked = document({ docNumber: 10501, name: 'Secret.docx', canOpen: false })
const host = window.location.host
const link = (n: number) => `healdan://open/${n}?server=${host}`

beforeEach(() => vi.mocked(launch).mockClear())

const main = () => screen.getByRole('main')
const row = async (name: RegExp) => within(await within(main()).findByRole('grid')).findByRole('row', { name })

function enter(target: HTMLElement) {
  act(() => target.focus())
  fireEvent.keyDown(target, { key: 'Enter' })
  fireEvent.keyUp(target, { key: 'Enter' })
}

const withDetails = (...docs: ReturnType<typeof document>[]): Routes => Object.assign({}, ...docs.map((doc) => detailsRoutes(doc)))

describe('opening a document from a list', () => {
  const folderRoutes: Routes = {
    'GET /v1/folders/7': folder({ id: 7, name: 'Correspondence' }),
    'GET /v1/folders/7/children': children([word, picture, locked]),
    ...withDetails(word, picture, locked),
  }

  it('opens in the app with Enter on a folder\'s row, and downloads a kind the helper does not open', async () => {
    await renderApp('/folders/7', folderRoutes)
    enter(await row(/Letter to Dunmere/))
    expect(launch).toHaveBeenLastCalledWith(link(10431))

    enter(await row(/Site Photo/))
    expect(launch).toHaveBeenLastCalledWith('/api/v1/documents/10500/content', 'Site Photo.png')
  })

  it('opens in the app with a double-click on a folder\'s row', async () => {
    await renderApp('/folders/7', folderRoutes)
    const target = await row(/Letter to Dunmere/)
    await userEvent.dblClick(within(target).getByRole('rowheader'))
    expect(launch).toHaveBeenCalledWith(link(10431))
  })

  it('only selects a document the person may not download', async () => {
    const { router } = await renderApp('/folders/7', folderRoutes)
    enter(await row(/Secret/))
    await waitFor(() => expect(router.history.location.search).toBe('?sel=10501'))
    expect(launch).not.toHaveBeenCalled()
  })

  it('opens in the app from Recent Documents', async () => {
    await renderApp('/', { 'GET /v1/documents/recent': page([word, picture]), ...withDetails(word, picture) })
    enter(await row(/Letter to Dunmere/))
    expect(launch).toHaveBeenLastCalledWith(link(10431))
  })

  it('opens in the app from the Recent Emails list too', async () => {
    await renderApp('/recent/emails', { 'GET /v1/documents/recent': page([word]), ...withDetails(word) })
    enter(await row(/Letter to Dunmere/))
    expect(launch).toHaveBeenLastCalledWith(link(10431))
  })

  it("opens in the app from a search's document results", async () => {
    await renderApp('/search?scope=documents&q=letter', { 'GET /v1/search': { items: [word, picture], offset: 0, limit: 100, total: 2 }, ...withDetails(word, picture) })
    enter(await row(/Letter to Dunmere/))
    expect(launch).toHaveBeenLastCalledWith(link(10431))
  })

  it("opens in the app from a # lookup's result", async () => {
    await renderApp('/doc/10431', { 'GET /v1/lookup': { docNumber: 10431, version: '1.0' }, ...withDetails(word) })
    enter(await row(/Letter to Dunmere/))
    expect(launch).toHaveBeenLastCalledWith(link(10431))
  })
})

describe("the toolbar's Open, Preview, Download and Check Out", () => {
  const routes: Routes = {
    'GET /v1/folders/7': folder({ id: 7, name: 'Correspondence' }),
    'GET /v1/folders/7/children': children([word, picture]),
    ...withDetails(word, picture),
  }
  const toolbar = async () => within(await screen.findByRole('toolbar', { name: 'Folder Actions' }))

  it('is disabled with nothing selected', async () => {
    await renderApp('/folders/7', routes)
    const bar = await toolbar()
    for (const name of ['Open', 'Preview', 'Download', 'Check Out']) expect((bar.getByRole('button', { name }) as HTMLButtonElement).disabled, name).toBe(true)
  })

  it('opens and downloads the selected document', async () => {
    await renderApp('/folders/7?sel=10431', routes)
    const bar = await toolbar()
    await waitFor(() => expect((bar.getByRole('button', { name: 'Open' }) as HTMLButtonElement).disabled).toBe(false))
    await userEvent.click(bar.getByRole('button', { name: 'Open' }))
    expect(launch).toHaveBeenLastCalledWith(link(10431))
    await userEvent.click(bar.getByRole('button', { name: 'Download' }))
    expect(launch).toHaveBeenLastCalledWith('/api/v1/documents/10431/content', 'Letter to Dunmere Pryce.docx')
  })

  it('checks the selected document out', async () => {
    const { api } = await renderApp('/folders/7?sel=10431', { ...routes, 'POST /v1/documents/10431/checkout': () => document({ ...word, hold: { kind: 'checkout', by: { id: 5, name: 'Priya Nair' }, since: '2026-11-03T10:00:00Z', device: null } }) })
    const bar = await toolbar()
    await waitFor(() => expect((bar.getByRole('button', { name: 'Check Out' }) as HTMLButtonElement).disabled).toBe(false))
    await userEvent.click(bar.getByRole('button', { name: 'Check Out' }))
    await waitFor(() => expect(api.callsTo('POST /v1/documents/10431/checkout')).toHaveLength(1))
  })

  it('is disabled for a document held by someone else', async () => {
    const held = document({ ...word, canEdit: false, hold: { kind: 'checkout', by: { id: 9, name: 'Alex Marsh' }, since: null, device: null } })
    await renderApp('/folders/7?sel=10431', { ...routes, 'GET /v1/folders/7/children': children([held]), ...withDetails(held) })
    const bar = await toolbar()
    await waitFor(() => expect((bar.getByRole('button', { name: 'Open' }) as HTMLButtonElement).disabled).toBe(false))
    expect((bar.getByRole('button', { name: 'Check Out' }) as HTMLButtonElement).disabled).toBe(true)
  })
})

describe('coming back after Open in App', () => {
  const routes: Routes = {
    'GET /v1/folders/7': folder({ id: 7, name: 'Correspondence' }),
    'GET /v1/folders/7/children': children([word]),
    ...withDetails(word),
  }

  // The helper checks the document out a few seconds after the link, and saves to it later still, out of the page's sight:
  // what the page has is marked stale, so the return to its window, and any view moved to after it, asks again. A return
  // alone leaves Recent Matters to its 30 seconds, so its asking again is the marking's
  it('marks the document and the lists stale, without asking again until the person comes back', async () => {
    const { api, queryClient } = await renderApp('/folders/7?sel=10431', routes)
    const bar = within(await screen.findByRole('toolbar', { name: 'Folder Actions' }))
    await waitFor(() => expect((bar.getByRole('button', { name: 'Open' }) as HTMLButtonElement).disabled).toBe(false))
    const asked = () => ['GET /v1/documents/10431', 'GET /v1/folders/7/children', 'GET /v1/matters/recent'].map((route) => api.callsTo(route).length)
    const before = asked()
    expect(before[2]).toBeGreaterThan(0)

    await userEvent.click(bar.getByRole('button', { name: 'Open' }))
    expect(launch).toHaveBeenLastCalledWith(link(10431))
    expect(queryClient.getQueryState(['documents', 10431])?.isInvalidated).toBe(true)
    const lists = queryClient.getQueryCache().findAll({ queryKey: ['folders', 7, 'children'] })
    expect(lists.length).toBeGreaterThan(0)
    expect(lists.every((query) => query.state.isInvalidated)).toBe(true)
    await settle()
    expect(asked()).toEqual(before)

    comeBack()
    await waitFor(() => expect(asked()).toEqual(before.map((n) => n + 1)))
  })
})
