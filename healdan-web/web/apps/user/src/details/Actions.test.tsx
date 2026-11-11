// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { launch } from '../actions/launch'
import { expectNoAxeViolations } from '../test/axe'
import { detailsRoutes, letter } from '../test/details'
import { children, document, folder, problem, type Routes } from '../test/fakeApi'
import { renderApp } from '../test/renderApp'

const correspondence = folder({ id: 7, name: 'Correspondence' })

function routes(doc = letter, over: Routes = {}): Routes {
  return {
    'GET /v1/folders/7': correspondence,
    'GET /v1/folders/7/children': children([doc]),
    ...detailsRoutes(doc),
    ...over,
  }
}

const details = () => screen.findByRole('region', { name: 'Details' })
const button = async (name: string) => within(await details()).findByRole('button', { name })

beforeEach(() => vi.mocked(launch).mockClear())

describe('Open and Download in the details', () => {
  it('opens a document the helper handles through its healdan: link, then says what to do if nothing opened', async () => {
    await renderApp('/folders/7?sel=10388', routes())
    await userEvent.click(await button('Open'))

    expect(launch).toHaveBeenCalledExactlyOnceWith(`healdan://open/10388?server=${window.location.host}`)
    const link = await screen.findByRole('link', { name: 'install the Healdan Helper' })
    expect(link.parentElement?.textContent).toBe('If nothing opened, install the Healdan Helper.')
    expect(link.getAttribute('href')).toBe('https://docs.healdan.com/user/office/installing-the-helper/')
  })

  it('downloads a kind the helper does not open, instead', async () => {
    const photo = document({ docNumber: 10500, name: 'Site Photo.png' })
    await renderApp('/folders/7?sel=10500', routes(photo))
    await userEvent.click(await button('Open'))

    expect(launch).toHaveBeenCalledExactlyOnceWith('/api/v1/documents/10500/content', 'Site Photo.png')
    expect(screen.queryByText(/If nothing opened/)).toBeNull()
  })

  it('downloads through a link to /content, which no script reads', async () => {
    const { api } = await renderApp('/folders/7?sel=10388', routes())
    await userEvent.click(await button('Download'))

    expect(launch).toHaveBeenCalledExactlyOnceWith('/api/v1/documents/10388/content', letter.name)
    expect(api.callsTo('GET /v1/documents/*/content')).toHaveLength(0)
  })

  it('disables both for a document the person may not download', async () => {
    await renderApp('/folders/7?sel=10388', routes(document({ ...letter, canOpen: false })))
    expect((await button('Open')).hasAttribute('disabled')).toBe(true)
    expect((await button('Download')).hasAttribute('disabled')).toBe(true)
  })
})

describe('Check Out, Check In and Release in the details', () => {
  const me = { id: 5, name: 'Priya Nair' }
  const held = (over: Partial<NonNullable<typeof letter.hold>> = {}) => ({ kind: 'checkout' as const, by: me, since: '2026-11-03T10:00:00Z', device: null, ...over })

  it('checks a free document out, and shows it held by you', async () => {
    let doc = letter
    const { api } = await renderApp(
      '/folders/7?sel=10388',
      routes(letter, {
        'GET /v1/documents/10388': () => doc,
        'POST /v1/documents/10388/checkout': () => (doc = document({ ...letter, hold: held() })),
      }),
    )
    await userEvent.click(await button('Check Out'))

    expect(await within(await details()).findByText(/Checked out by you since/)).toBeTruthy()
    expect(api.callsTo('POST /v1/documents/10388/checkout')).toHaveLength(1)
    expect(within(await details()).queryByRole('button', { name: 'Check Out' })).toBeNull()
    expect(within(await details()).getByRole('button', { name: 'Check In' })).toBeTruthy()
    expect(within(await details()).getByRole('button', { name: 'Release' })).toBeTruthy()
  })

  it("shows who has someone else's check-out and since when, and offers no Check Out or take-over", async () => {
    const theirs = document({ ...letter, canEdit: false, hold: held({ by: { id: 9, name: 'Alex Marsh' } }) })
    await renderApp('/folders/7?sel=10388', routes(theirs))

    const panel = await details()
    expect(await within(panel).findByText(/Checked out by Alex Marsh since/)).toBeTruthy()
    expect(within(panel).getByText(/Classic Interface \(Unlock\)/)).toBeTruthy()
    expect(within(panel).queryByRole('button', { name: /Check Out|Check In|Release/ })).toBeNull()
  })

  it('says a check-out you hold on a computer is released there, and offers no way to take it over', async () => {
    const mine = document({ ...letter, canEdit: false, hold: held({ device: { id: 'd1', name: 'Healdan Helper on MacBook', app: 'Word' } }) })
    await renderApp('/folders/7?sel=10388', routes(mine))

    const panel = await details()
    expect(await within(panel).findByText(/Checked out by you on Healdan Helper on MacBook since/)).toBeTruthy()
    expect(within(panel).getByText(/Release it on that computer, or in the Classic Interface \(Unlock\)/)).toBeTruthy()
    expect(within(panel).queryByRole('button', { name: /Check Out|Check In|Release/ })).toBeNull()
  })

  it('says a lock is undone in the Classic Interface', async () => {
    const locked = document({ ...letter, canEdit: false, hold: held({ kind: 'lock', by: { id: 9, name: 'Alex Marsh' } }) })
    await renderApp('/folders/7?sel=10388', routes(locked))
    expect(await within(await details()).findByText(/Locked by Alex Marsh since/)).toBeTruthy()
    expect(within(await details()).getByText(/Unlock it in the Classic Interface/)).toBeTruthy()
  })

  it('releases your own web check-out', async () => {
    let doc = document({ ...letter, hold: held() })
    const { api } = await renderApp(
      '/folders/7?sel=10388',
      routes(doc, {
        'GET /v1/documents/10388': () => doc,
        'DELETE /v1/documents/10388/checkout': () => (doc = document({ ...letter, hold: null })),
      }),
    )
    await userEvent.click(await button('Release'))

    await waitFor(() => expect(within(details_()).queryByText(/Checked out by you/)).toBeNull())
    expect(api.callsTo('DELETE /v1/documents/10388/checkout')).toHaveLength(1)
    expect(await button('Check Out')).toBeTruthy()
  })

  it('checks in a file as the next snapshot of the version it started from, with the document\'s own name', async () => {
    const mine = document({ ...letter, version: '2.1', hold: held() })
    let sent: FormData | undefined
    const { api } = await renderApp(
      '/folders/7?sel=10388',
      routes(mine, {
        'POST /v1/documents/10388/versions': ({ body }: { body?: unknown }) => {
          sent = body as FormData
          return document({ ...letter, version: '2.2', hold: null })
        },
      }),
    )
    const input = (await details()).querySelector<HTMLInputElement>('input[type="file"]')!
    await userEvent.upload(input, new File(['new text'], 'whatever.pdf', { type: 'application/pdf' }))

    await waitFor(() => expect(api.callsTo('POST /v1/documents/10388/versions')).toHaveLength(1))
    expect(await (sent!.get('version') as Blob).text().then(JSON.parse)).toEqual({ kind: 'snapshot', name: letter.name, ifHeld: true, ifVersion: '2.1' })
    expect((sent!.get('file') as File).name).toBe('whatever.pdf')
  })

  it("shows the server's problem from the catalogue when a check-out is refused, and asks again for the hold", async () => {
    const { api } = await renderApp('/folders/7?sel=10388', routes(letter, { 'POST /v1/documents/10388/checkout': problem(409, 'checked-out') }))
    await userEvent.click(await button('Check Out'))

    expect(await screen.findByText("It's checked out.")).toBeTruthy()
    expect(screen.queryByText(/A server sentence/)).toBeNull()
    await waitFor(() => expect(api.callsTo('GET /v1/documents/10388').length).toBeGreaterThan(1))
  })

  it('disables Check Out when the person may not write the document', async () => {
    await renderApp('/folders/7?sel=10388', routes(document({ ...letter, canEdit: false })))
    expect((await button('Check Out')).hasAttribute('disabled')).toBe(true)
  })

  it('passes axe with a hold shown', async () => {
    const theirs = document({ ...letter, canEdit: false, hold: held({ by: { id: 9, name: 'Alex Marsh' } }) })
    await renderApp('/folders/7?sel=10388', routes(theirs))
    await within(await details()).findByText(/Checked out by Alex Marsh/)
    await expectNoAxeViolations(await details())
  })
})

// The panel is rendered once; looked up again after a change
function details_(): HTMLElement {
  return screen.getByRole('region', { name: 'Details' })
}

void fireEvent
