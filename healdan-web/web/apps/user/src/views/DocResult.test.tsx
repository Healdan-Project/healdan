// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { cleanup, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { expectNoAxeViolations } from '../test/axe'
import { detailsRoutes, letter } from '../test/details'
import { page, problem, versionRow, type Routes } from '../test/fakeApi'
import { renderApp } from '../test/renderApp'

function routes(over: Routes = {}): Routes {
  return {
    'GET /v1/lookup': ({ query }: { query?: Record<string, unknown> }) => ({ docNumber: 10388, version: query?.q === '#10388.2' ? '2.1' : '2.1' }),
    ...detailsRoutes(letter),
    ...over,
  }
}

const main = () => screen.getByRole('main')

describe('DocResult', () => {
  it('shows a result list with just that document, selected, with its details open', async () => {
    const { api } = await renderApp('/doc/10388', routes())

    expect(await within(main()).findByRole('heading', { level: 1, name: 'Document # 10388' })).toBeTruthy()
    const grid = await within(main()).findByRole('grid')
    const rows = within(grid).getAllByRole('row').slice(1)
    expect(rows).toHaveLength(1)
    expect(rows[0]!.getAttribute('aria-selected')).toBe('true')
    expect(within(rows[0]!).getByText('Letter from Dunmere Pryce.pdf')).toBeTruthy()

    const panel = await screen.findByRole('region', { name: 'Details' })
    expect(await within(panel).findByRole('heading', { level: 2, name: 'Letter from Dunmere Pryce.pdf' })).toBeTruthy()
    // The # goes to the server as typed, encoded as a query value
    expect(api.callsTo('GET /v1/lookup')[0]!.query).toEqual({ q: '#10388' })
    // No version was named, so the details start on Properties, and none is marked in Versions
    expect(within(panel).getByRole('tab', { name: 'Properties' }).getAttribute('aria-selected')).toBe('true')
    await userEvent.click(await within(panel).findByRole('tab', { name: /^Versions/ }))
    await within(panel).findAllByRole('listitem')
    const versions = within(panel).getByRole('tabpanel', { name: /^Versions/ })
    expect(within(versions).getAllByRole('listitem').every((item) => item.getAttribute('aria-current') === null)).toBe(true)
  })

  // Spec 6.3: "#10388.2 selects version 2 in the details", which are in the Versions tab since the owner's dogfooding punch
  // list (2026-11-14): a # that names a version opens it
  it('opens the Versions tab with the version in the address marked', async () => {
    const { api } = await renderApp('/doc/10388.2', routes())

    const panel = await screen.findByRole('region', { name: 'Details' })
    await within(panel).findAllByRole('listitem')
    expect(within(panel).getByRole('tab', { name: /^Versions/ }).getAttribute('aria-selected')).toBe('true')
    const versions = within(panel).getByRole('tabpanel', { name: /^Versions/ })
    const marked = within(versions).getAllByRole('listitem').filter((item) => item.getAttribute('aria-current') === 'true')
    expect(marked).toHaveLength(1)
    // The server answers a bare major with its latest snapshot, 2.1 here, not the whole version 2.0
    expect(marked[0]!.textContent).toContain('Version 2.1')
    expect(api.callsTo('GET /v1/lookup')[0]!.query).toEqual({ q: '#10388.2' })
  })

  it('takes the other forms of a doc #', async () => {
    const { api } = await renderApp('/doc/10388v2.1', routes())

    await screen.findByRole('region', { name: 'Details' })
    expect(api.callsTo('GET /v1/lookup')[0]!.query).toEqual({ q: '#10388v2.1' })
  })

  it('says "No document # 10388 that you can see." for a document that is not there, and the same for one that is not readable', async () => {
    const gone = problem(404, 'not-found', 'No document #10388')
    await renderApp('/doc/10388', routes({ 'GET /v1/lookup': gone }))
    const first = (await within(main()).findByText('No document # 10388 that you can see.')).textContent

    // The same answer, whatever its words: the app doesn't tell the two apart
    const hidden = problem(404, 'not-found', 'Something else the server might say')
    cleanup()
    await renderApp('/doc/10388', routes({ 'GET /v1/lookup': hidden }))
    const second = (await within(main()).findByText('No document # 10388 that you can see.')).textContent

    expect(second).toBe(first)
    expect(screen.queryByRole('region', { name: 'Details' })).toBeNull()
  })

  it('shows the number as typed, and the same words for a version that is not there', async () => {
    await renderApp('/doc/10388.9', routes({ 'GET /v1/lookup': problem(404, 'not-found', 'Document #10388 has no version 9') }))

    expect(await within(main()).findByText('No document # 10388 that you can see.')).toBeTruthy()
  })

  it('shows the not-found view for something that is not a doc #', async () => {
    await renderApp('/doc/abc', routes({ 'GET /v1/lookup': problem(400, 'bad-request', "'abc' isn't a document number.") }))

    expect(await within(main()).findByText("Not found, or you can't see it.")).toBeTruthy()
  })

  it('shows a try-again for a failure that is not a 404', async () => {
    await renderApp('/doc/10388', routes({ 'GET /v1/lookup': problem(500, 'server-error') }))

    expect(await within(main()).findByText('Something went wrong.')).toBeTruthy()
    expect(within(main()).getByRole('button', { name: 'Try Again' })).toBeTruthy()
  })

  it('has Location, linking to the folder, since a pasted link has no breadcrumb', async () => {
    await renderApp('/doc/10388', routes())
    const panel = await screen.findByRole('region', { name: 'Details' })

    const link = await within(panel).findByRole('link', { name: 'Correspondence' })
    expect(link.getAttribute('href')).toBe('/app/folders/111')
  })

  it('shows the # words when the document goes away after the lookup found it', async () => {
    await renderApp('/doc/10388', routes({ 'GET /v1/documents/10388': problem(404, 'not-found') }))

    expect(await within(main()).findByText('No document # 10388 that you can see.')).toBeTruthy()
  })

  describe('a long timeline', () => {
    // 250 rows, newest first: 250.0 down to 1.0, as the server pages them 200 at a time
    const timeline = Array.from({ length: 250 }, (_, i) => versionRow({ version: `${250 - i}.0`, fileVersion: `${250 - i}.0` }))
    const long = (version: string) =>
      routes({
        'GET /v1/lookup': { docNumber: 10388, version },
        'GET /v1/documents/10388/versions': ({ query }: { query?: Record<string, unknown> }) => {
          const offset = Number(query?.offset ?? 0)
          const limit = Number(query?.limit ?? 50)
          return { ...page(timeline.slice(offset, offset + limit)), offset, limit, total: 250 }
        },
      })

    it('asks for 200 a page and offers the rest', async () => {
      const { api } = await renderApp('/doc/10388', long('250.0'))
      const panel = await screen.findByRole('region', { name: 'Details' })

      // The tab counts them all, not the page
      await userEvent.click(await within(panel).findByRole('tab', { name: 'Versions (250)' }))
      await within(panel).findAllByRole('listitem')
      const versions = within(panel).getByRole('tabpanel', { name: 'Versions (250)' })
      expect(within(versions).getAllByRole('listitem')).toHaveLength(200)
      expect(api.callsTo('GET /v1/documents/10388/versions')[0]!.query).toMatchObject({ limit: 200 })

      await userEvent.click(within(versions).getByRole('button', { name: 'Show More Versions' }))
      await waitFor(() => expect(within(versions).getAllByRole('listitem')).toHaveLength(250))
      expect(within(versions).queryByRole('button', { name: 'Show More Versions' })).toBeNull()
    })

    it('loads on until the version a # names is shown and marked', async () => {
      await renderApp('/doc/10388.20', long('20.0'))
      const panel = await screen.findByRole('region', { name: 'Details' })

      await waitFor(() => {
        const marked = within(panel).getAllByRole('listitem').filter((item) => item.getAttribute('aria-current') === 'true')
        expect(marked).toHaveLength(1)
        expect(marked[0]!.textContent).toContain('Version 20')
      })
    })
  })

  it('passes axe', async () => {
    await renderApp('/doc/10388.2', routes())
    const panel = await screen.findByRole('region', { name: 'Details' })
    await within(panel).findAllByRole('listitem')
    await waitFor(() => expect(within(main()).getAllByRole('row')).toHaveLength(2))
    await expectNoAxeViolations(document.body)
  })
})
