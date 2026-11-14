// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { act, fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { expectNoAxeViolations } from '../test/axe'
import { detailsRoutes, letter, propertiesOf as fields } from '../test/details'
import { children, document, favorite, folder, page, problem, type Routes } from '../test/fakeApi'
import { renderApp } from '../test/renderApp'
import { formatDateTime } from '@healdan/ui'
import { THUMBNAIL_RETRY_MS } from './DetailsPanel'
import detailsCss from './details.module.css?raw'
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
// The tabs are there while their answers come, so wait for what they list. Only the selected tab's panel is drawn.
async function propertiesOf(panel: HTMLElement) {
  await within(panel).findAllByRole('term')
  return within(panel).getByRole('tabpanel', { name: 'Properties' })
}
async function versionsOf(panel: HTMLElement) {
  await userEvent.click(await within(panel).findByRole('tab', { name: /^Versions/ }))
  await within(panel).findAllByRole('listitem')
  return within(panel).getByRole('tabpanel', { name: /^Versions/ })
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
    for (const name of ['Open', 'Download', 'Preview', 'Copy Link']) {
      expect((within(panel).getByRole('button', { name }) as HTMLButtonElement).disabled).toBe(false)
    }
    // Edit Properties is in the Properties tab, not among the actions, where it read as editing the document. It's for
    // a document whose properties the person may change: none of these fields says it may be.
    const properties = await propertiesOf(panel)
    expect((within(properties).getByRole('button', { name: 'Edit Properties' }) as HTMLButtonElement).disabled).toBe(true)
    expect(within(panel).queryByRole('button', { name: 'Edit' })).toBeNull()
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
    await userEvent.click(within(panel).getByRole('tab', { name: 'Versions' }))
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

  it('passes axe, on either tab', async () => {
    await renderApp('/folders/7?sel=10388', routes())
    const panel = await details()
    await propertiesOf(panel)
    await expectNoAxeViolations(panel)
    await versionsOf(panel)
    await expectNoAxeViolations(panel)
  })
})

// The helper's Open window has a fixed header and two tabs, and so do the details (the owner's dogfooding punch list,
// 2026-11-14)
describe('the Properties and Versions tabs', () => {
  const other = document({ docNumber: 10431, name: 'Letter to Dunmere Pryce.docx' })
  const both = (over: Routes = {}) => routes({ 'GET /v1/folders/7/children': children([letter, other]), ...detailsRoutes(other), ...over })
  const tab = (panel: HTMLElement, name: string | RegExp) => within(panel).getByRole('tab', { name })

  it('start on Properties, with the versions counted on the Versions tab', async () => {
    await renderApp('/folders/7?sel=10388', routes())
    const panel = await details()
    await propertiesOf(panel)

    expect(within(panel).getByRole('tablist', { name: 'Properties and Versions' })).toBeTruthy()
    expect(tab(panel, 'Properties').getAttribute('aria-selected')).toBe('true')
    await waitFor(() => expect(tab(panel, 'Versions (3)').getAttribute('aria-selected')).toBe('false'))
    // Only the selected tab is drawn
    expect(within(panel).queryAllByRole('listitem')).toHaveLength(0)
  })

  it('say only "Versions" while the versions come', async () => {
    await renderApp('/folders/7?sel=10388', routes({ 'GET /v1/documents/10388/versions': () => new Promise(() => {}) }))
    const panel = await details()
    await propertiesOf(panel)
    expect(tab(panel, 'Versions')).toBeTruthy()
  })

  it('come after the header, whose actions show on both', async () => {
    await renderApp('/folders/7?sel=10388', routes())
    const panel = await details()
    const list = within(panel).getByRole('tablist')
    for (const name of ['Open', 'Download', 'Copy Link']) {
      const button = within(panel).getByRole('button', { name })
      expect(button.compareDocumentPosition(list) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    }
    await versionsOf(panel)
    expect(within(panel).getByRole('button', { name: 'Copy Link' })).toBeTruthy()
  })

  it('stay on the tab the person left while they select other documents', async () => {
    await renderApp('/folders/7?sel=10388', both())
    await versionsOf(await details())

    const row = await within(screen.getByRole('main')).findByRole('row', { name: /Letter to Dunmere/ })
    await userEvent.click(within(row).getByRole('rowheader'))
    const panel = await details()
    await within(panel).findByRole('heading', { level: 2, name: 'Letter to Dunmere Pryce.docx' })
    expect(tab(panel, /^Versions/).getAttribute('aria-selected')).toBe('true')
    expect(await within(panel).findAllByRole('listitem')).toHaveLength(3)
  })
})

// While the document is held, a section of its own says who has it, since when, and where (the owner's dogfooding punch
// list, 2026-11-14). The person is Priya Nair (5), as the fake API's /me says.
describe('Check-Out Info', () => {
  const since = '2026-11-03T10:00:00Z'
  const onMac = { name: 'Healdan Helper on MacBook', app: 'Excel' }
  type Hold = NonNullable<S['DocumentView']['hold']>
  const held = (hold: Partial<Hold>) => document({ ...letter, hold: { kind: 'checkout', by: { id: 5, name: 'Priya Nair' }, since, device: null, heldOn: null, ...hold } })
  async function sectionOf(doc: S['DocumentView'], name = 'Check-Out Info') {
    await renderApp('/folders/7?sel=10388', routes({ 'GET /v1/folders/7/children': children([doc]), ...detailsRoutes(doc) }))
    return within(await details()).findByRole('group', { name })
  }
  const rows = (section: HTMLElement) =>
    Object.fromEntries(within(section).getAllByRole('term').map((term) => [term.textContent, term.nextElementSibling?.textContent]))

  it('is not there for a free document', async () => {
    await renderApp('/folders/7?sel=10388', routes())
    const panel = await details()
    await propertiesOf(panel)
    expect(within(panel).queryByRole('group', { name: /Info$/ })).toBeNull()
  })

  it('shows your own check-out on a computer with all four rows', async () => {
    const section = await sectionOf(held({ device: { id: 'mac-1', ...onMac }, heldOn: onMac }))
    expect(rows(section)).toEqual({
      User: 'Priya Nair (You)',
      'Check-Out Time': formatDateTime(since),
      Application: 'Excel',
      Computer: 'Healdan Helper on MacBook',
    })
  })

  it("shows only who has someone else's check-out and since when, as the API tells nobody else where it is", async () => {
    const section = await sectionOf(held({ by: { id: 9, name: 'Alex Marsh' } }))
    expect(rows(section)).toEqual({ User: 'Alex Marsh', 'Check-Out Time': formatDateTime(since) })
  })

  it("shows an administrator where someone else's check-out is held, from heldOn alone", async () => {
    const section = await sectionOf(held({ by: { id: 9, name: 'Alex Marsh' }, heldOn: onMac }))
    expect(rows(section)).toEqual({ User: 'Alex Marsh', 'Check-Out Time': formatDateTime(since), Application: 'Excel', Computer: 'Healdan Helper on MacBook' })
  })

  it('shows your own check-out from the web page with User and Check-Out Time, over Check In and Release', async () => {
    const section = await sectionOf(held({}))
    expect(rows(section)).toEqual({ User: 'Priya Nair (You)', 'Check-Out Time': formatDateTime(since) })
    const checkIn = within(await details()).getByRole('button', { name: 'Check In' })
    expect(section.compareDocumentPosition(checkIn) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(within(await details()).getByRole('button', { name: 'Release' })).toBeTruthy()
  })

  it('is Lock Info for a lock, with its Lock Time', async () => {
    const section = await sectionOf(held({ kind: 'lock', by: { id: 9, name: 'Alex Marsh' } }), 'Lock Info')
    expect(rows(section)).toEqual({ User: 'Alex Marsh', 'Lock Time': formatDateTime(since) })
    expect(within(await details()).queryByRole('group', { name: 'Check-Out Info' })).toBeNull()
  })

  it('leaves out a row the API gives no value for', async () => {
    const section = await sectionOf(held({ by: null, since: null, heldOn: { name: 'Healdan Helper on MacBook', app: null } }))
    expect(rows(section)).toEqual({ Computer: 'Healdan Helper on MacBook' })
  })

  it('sits above the tabs, so it shows on both, and is boxed', async () => {
    const section = await sectionOf(held({ by: { id: 9, name: 'Alex Marsh' } }))
    const panel = await details()
    expect(section.compareDocumentPosition(within(panel).getByRole('tablist')) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    await versionsOf(panel)
    expect(within(panel).getByRole('group', { name: 'Check-Out Info' })).toBe(section)
    expect(section.className).toContain('boxed')
    // jsdom draws no style, so the stylesheet is read: a border and the sidebar's shading, which every text colour of the
    // panel reaches AA on, in both themes (tokens.test.ts)
    const boxed = /\.boxed\s*\{([^}]*)\}/.exec(detailsCss)?.[1] ?? ''
    expect(boxed).toMatch(/border:\s*1px solid var\(--hd-line\)/)
    expect(boxed).toMatch(/border-radius:\s*var\(--hd-radius\)/)
    expect(boxed).toMatch(/background:\s*var\(--hd-sidebar\)/)
  })

  it('passes axe', async () => {
    await sectionOf(held({ device: { id: 'mac-1', ...onMac }, heldOn: onMac }))
    await expectNoAxeViolations(await details())
  })
})
