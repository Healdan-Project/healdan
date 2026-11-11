// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { cleanup, screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { expectNoAxeViolations } from '../test/axe'
import { detailsRoutes, letter } from '../test/details'
import { problem, type Routes } from '../test/fakeApi'
import { renderApp } from '../test/renderApp'

function routes(over: Routes = {}): Routes {
  return {
    'GET /v1/lookup': ({ query }: { query?: Record<string, unknown> }) => ({ docNumber: 10388, version: query?.q === '#10388.2' ? '2.0' : '2.1' }),
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
    // No version was named, so none is marked in Versions
    await within(panel).findAllByRole('listitem')
    const versions = within(panel).getByRole('group', { name: 'Versions' })
    expect(within(versions).getAllByRole('listitem').every((item) => item.getAttribute('aria-current') === null)).toBe(true)
  })

  it('selects the version in the address in Versions', async () => {
    const { api } = await renderApp('/doc/10388.2', routes())

    const panel = await screen.findByRole('region', { name: 'Details' })
    await within(panel).findAllByRole('listitem')
    const versions = within(panel).getByRole('group', { name: 'Versions' })
    const marked = within(versions).getAllByRole('listitem').filter((item) => item.getAttribute('aria-current') === 'true')
    expect(marked).toHaveLength(1)
    expect(marked[0]!.textContent).toContain('Version 2')
    expect(marked[0]!.textContent).not.toContain('Version 2.1')
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

  it('passes axe', async () => {
    await renderApp('/doc/10388.2', routes())
    const panel = await screen.findByRole('region', { name: 'Details' })
    await within(panel).findAllByRole('listitem')
    await within(panel).findAllByRole('term')
    await waitFor(() => expect(within(main()).getAllByRole('row')).toHaveLength(2))
    await expectNoAxeViolations(document.body)
  })
})
