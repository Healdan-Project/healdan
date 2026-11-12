// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { act, fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { expectNoAxeViolations } from '../test/axe'
import { detailsRoutes, letter, propertiesOf as fields } from '../test/details'
import { children, document, favorite, folder, page, problem, type Routes } from '../test/fakeApi'
import { renderApp } from '../test/renderApp'
import { THUMBNAIL_RETRY_MS } from './DetailsPanel'
import type { components } from '@healdan/api'

type S = components['schemas']

const correspondence = folder({ id: 7, name: 'Correspondence' })

function routes(over: Routes = {}): Routes {
  return {
    'GET /v1/folders/7': correspondence,
    'GET /v1/folders/7/children': children([letter]),
    ...detailsRoutes(letter),
    ...over,
  }
}

const details = () => screen.findByRole('region', { name: 'Details' })
// The groups are there while their answers come, so wait for what they list
async function propertiesOf(panel: HTMLElement) {
  await within(panel).findAllByRole('term')
  return within(panel).getByRole('group', { name: 'Properties' })
}
async function versionsOf(panel: HTMLElement) {
  await within(panel).findAllByRole('listitem')
  return within(panel).getByRole('group', { name: 'Versions' })
}

afterEach(() => Object.defineProperty(navigator, 'clipboard', { value: undefined, configurable: true }))

describe('DetailsPanel', () => {
  it('shows the thumbnail with Preview, the name, the doc #, version and size, and the actions', async () => {
    await renderApp('/folders/7?sel=10388', routes())
    const panel = await details()

    expect(await within(panel).findByRole('heading', { level: 2, name: 'Letter from Dunmere Pryce.pdf' })).toBeTruthy()
    expect(within(panel).getByText('# 10388 · Version 2.1 · 209 KB')).toBeTruthy()
    const thumbnail = panel.querySelector('img')
    // Addressed by its file version, so a new version's never comes from the browser's cache
    expect(thumbnail?.getAttribute('src')).toBe('/api/v1/documents/10388/thumbnail?v=2.1')
    // Edit is for a document whose properties the person may change: none of these fields says it may be
    expect((within(panel).getByRole('button', { name: 'Edit' }) as HTMLButtonElement).disabled).toBe(true)
    for (const name of ['Open', 'Download', 'Preview', 'Copy Link']) {
      expect((within(panel).getByRole('button', { name }) as HTMLButtonElement).disabled).toBe(false)
    }
  })

  it('adds the document to Favorites with the star by its name, which Tab reaches, and takes it out again', async () => {
    let favorites: S['FavoriteView'][] = []
    const sent: unknown[] = []
    const { api } = await renderApp(
      '/folders/7?sel=10388',
      routes({
        'GET /v1/me/favorites': () => page(favorites),
        'POST /v1/me/favorites': ({ body }: { body?: unknown }) => {
          sent.push(body)
          favorites = [favorite(letter, 960)]
          return favorites[0]
        },
        'DELETE /v1/me/favorites/*': () => {
          favorites = []
          return undefined
        },
      }),
    )
    const panel = await details()
    const add = await within(panel).findByRole('button', { name: 'Add Document to Favorites' })
    await waitFor(() => expect((add as HTMLButtonElement).disabled).toBe(false))
    // After the thumbnail's Preview, Tab comes to the star
    within(panel).getByRole('button', { name: 'Preview' }).focus()
    await userEvent.tab()
    expect(globalThis.document.activeElement).toBe(add)

    await userEvent.keyboard('{Enter}')
    const remove = await within(panel).findByRole('button', { name: 'Remove Document from Favorites' })
    expect(sent).toEqual([{ docNumber: 10388 }])
    // My Favorites shows it too
    expect(await within(screen.getByRole('navigation', { name: 'Navigation' })).findByRole('link', { name: /Letter from Dunmere Pryce/ })).toBeTruthy()

    await userEvent.click(remove)
    expect(await within(panel).findByRole('button', { name: 'Add Document to Favorites' })).toBeTruthy()
    expect(api.callsTo('DELETE /v1/me/favorites/960')).toHaveLength(1)
  })

  it('lists the properties in order, without Location outside search results and without the email fields of a document', async () => {
    await renderApp('/folders/7?sel=10388', routes())
    const panel = await details()

    const list = await propertiesOf(panel)
    const labels = within(list).getAllByRole('term').map((term) => term.textContent)
    expect(labels).toEqual(['Client', 'Matter', 'Class', 'Subclass', 'Author', 'Typist', 'Date Filed'])
    const value = (label: string) => within(list).getByText(label).nextElementSibling?.textContent
    expect(value('Client')).toBe('1001 – Saltmarsh Shipping Ltd')
    expect(value('Matter')).toBe('1001.1 – Charter Dispute')
    expect(value('Class')).toBe('Letters')
    expect(value('Subclass')).toBe('Incoming')
    expect(value('Author')).toBe('Jamie Clarke')
    expect(value('Typist')).toBe('Priya Nair')
    expect(value('Date Filed')).toBe('Sep 29, 2026')
  })

  it("shows an email's From, To, Sent and Received", async () => {
    const email = document({ docNumber: 10500, name: 'Re Laytime.eml', size: 4_000 })
    await renderApp(
      '/folders/7?sel=10500',
      routes({
        'GET /v1/folders/7/children': children([email]),
        ...detailsRoutes(email, {
          'GET /v1/documents/10500/properties': page(
            fields({
              emailfrom: 'Dunmere Pryce <dp@example.test>',
              emailto: 'priya@example.test',
              emailsent: '2026-10-01T08:15:00Z',
              emailreceived: '2026-10-01T08:16:00Z',
            }),
          ),
        }),
      }),
    )
    const panel = await details()

    const list = await propertiesOf(panel)
    expect(within(list).getAllByRole('term').map((term) => term.textContent)).toEqual([
      'Client',
      'Matter',
      'Class',
      'Subclass',
      'Author',
      'Typist',
      'Date Filed',
      'From',
      'To',
      'Sent',
      'Received',
    ])
    expect(within(list).getByText('Dunmere Pryce <dp@example.test>')).toBeTruthy()
  })

  it('lists the versions, newest first', async () => {
    await renderApp('/folders/7?sel=10388', routes())
    const panel = await details()

    const list = await versionsOf(panel)
    const rows = within(list).getAllByRole('listitem')
    expect(rows.map((row) => within(row).getByText(/^Version /).textContent)).toEqual(['Version 2.1', 'Version 2', 'Version 1'])
    expect(rows[0]!.textContent).toContain('Jamie Clarke')
    expect(rows[0]!.textContent).toContain('Fixed the date')
    expect(rows[1]!.textContent).toContain('Priya Nair')
    expect(rows.every((row) => row.getAttribute('aria-current') === null)).toBe(true)
  })

  it('shows a plain file icon, not a broken image, when the thumbnail is missing or the document cannot be opened', async () => {
    await renderApp('/folders/7?sel=10388', routes())
    const panel = await details()
    const img = await waitFor(() => {
      const found = panel.querySelector('img')
      expect(found).toBeTruthy()
      return found!
    })

    // A document with no thumbnail yet answers 404 and the browser fires error on the image
    fireEvent.error(img)

    await waitFor(() => expect(panel.querySelector('img')).toBeNull())
    expect(panel.querySelector('.thumb svg')).toBeTruthy()
  })

  it('asks for a thumbnail that was missing again a little later, as the server makes it after a filing', async () => {
    await renderApp('/folders/7?sel=10388', routes())
    const panel = await details()
    const img = await waitFor(() => {
      const found = panel.querySelector('img')
      expect(found).toBeTruthy()
      return found!
    })
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    try {
      fireEvent.error(img)
      await vi.waitFor(() => expect(panel.querySelector('img')).toBeNull())
      act(() => vi.advanceTimersByTime(THUMBNAIL_RETRY_MS))
      expect(panel.querySelector('img')?.getAttribute('src')).toBe('/api/v1/documents/10388/thumbnail?v=2.1')
    } finally {
      vi.useRealTimers()
    }
  })

  it('asks for the new thumbnail when a new version is saved', async () => {
    let doc = letter
    const { queryClient } = await renderApp('/folders/7?sel=10388', routes({ 'GET /v1/documents/10388': () => doc }))
    const panel = await details()
    await waitFor(() => expect(panel.querySelector('img')?.getAttribute('src')).toBe('/api/v1/documents/10388/thumbnail?v=2.1'))
    doc = { ...letter, version: '3.0', fileVersion: '3.0' }
    await act(() => queryClient.invalidateQueries({ queryKey: ['documents', 10388] }))
    await waitFor(() => expect(panel.querySelector('img')?.getAttribute('src')).toBe('/api/v1/documents/10388/thumbnail?v=3.0'))
  })

  it('asks for no thumbnail of a document the person cannot open', async () => {
    const locked = document({ docNumber: 10388, name: 'Letter.pdf', canOpen: false })
    await renderApp('/folders/7?sel=10388', routes({ 'GET /v1/folders/7/children': children([locked]), ...detailsRoutes(locked) }))
    const panel = await details()

    await within(panel).findByRole('heading', { level: 2, name: 'Letter.pdf' })
    expect(panel.querySelector('img')).toBeNull()
    expect(panel.querySelector('.thumb svg')).toBeTruthy()
  })

  it('says so when the document cannot be loaded, with Try Again', async () => {
    await renderApp('/folders/7?sel=10388', routes({ 'GET /v1/documents/10388': problem(404, 'not-found') }))
    const panel = await details()

    expect(await within(panel).findByText("Not found, or you can't see it.")).toBeTruthy()
    expect(within(panel).getByRole('button', { name: 'Try Again' })).toBeTruthy()
  })

  it('keeps the rest of the details when the properties or the versions cannot be loaded', async () => {
    await renderApp(
      '/folders/7?sel=10388',
      routes({ 'GET /v1/documents/10388/properties': problem(500, 'server-error'), 'GET /v1/documents/10388/versions': problem(500, 'server-error') }),
    )
    const panel = await details()

    expect(await within(panel).findByText("The properties aren't available right now.")).toBeTruthy()
    expect(await within(panel).findByText("The versions aren't available right now.")).toBeTruthy()
    expect(within(panel).getByRole('heading', { level: 2, name: 'Letter from Dunmere Pryce.pdf' })).toBeTruthy()
  })

  describe('Copy Link', () => {
    it('puts https://host/app/doc/10388 on the clipboard and says "Link copied"', async () => {
      const writeText = vi.fn().mockResolvedValue(undefined)
      await renderApp('/folders/7?sel=10388', routes())
      Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
      const panel = await details()

      await userEvent.click(within(panel).getByRole('button', { name: 'Copy Link' }))

      expect(writeText).toHaveBeenCalledWith(`${window.location.origin}/app/doc/10388`)
      expect(await screen.findByText('Link copied')).toBeTruthy()
    })

    it("says the link couldn't be copied when the clipboard is unavailable", async () => {
      await renderApp('/folders/7?sel=10388', routes())
      const panel = await details()

      await userEvent.click(within(panel).getByRole('button', { name: 'Copy Link' }))

      expect(await screen.findByText("The link couldn't be copied.")).toBeTruthy()
    })
  })

  it('passes axe', async () => {
    await renderApp('/folders/7?sel=10388', routes())
    const panel = await details()
    await versionsOf(panel)
    await propertiesOf(panel)
    await expectNoAxeViolations(panel)
  })
})
