// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { launch, launchDownload } from '../actions/launch'
import { expectNoAxeViolations } from '../test/axe'
import { detailsRoutes, letter } from '../test/details'
import { children, document, folder, problem, type Routes } from '../test/fakeApi'
import { renderApp } from '../test/renderApp'
import { cacheMessage } from '../organising/testing'

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

beforeEach(() => {
  vi.mocked(launch).mockClear()
  vi.mocked(launchDownload).mockClear()
})

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

    expect(launchDownload).toHaveBeenCalledExactlyOnceWith('/api/v1/documents/10500/content', expect.any(Function))
    expect(screen.queryByText(/If nothing opened/)).toBeNull()
  })

  it("downloads through a hidden frame's navigation to /content, which no script reads", async () => {
    const { api } = await renderApp('/folders/7?sel=10388', routes())
    await userEvent.click(await button('Download'))

    expect(launchDownload).toHaveBeenCalledExactlyOnceWith('/api/v1/documents/10388/content', expect.any(Function))
    expect(api.callsTo('GET /v1/documents/*/content')).toHaveLength(0)
  })

  it('shows the signed-out page when a download finds the session has ended', async () => {
    await renderApp('/folders/7?sel=10388', routes())
    await userEvent.click(await button('Download'))

    // What launchDownload calls for a 401 in its frame, which no request of the API client's sees
    const onSignedOut = vi.mocked(launchDownload).mock.lastCall?.[1]
    act(() => onSignedOut?.())

    expect(await screen.findByRole('heading', { level: 1, name: "You've been signed out." })).toBeTruthy()
    expect(screen.queryByRole('region', { name: 'Details' })).toBeNull()
  })

  it('disables both for a document the person may not download', async () => {
    await renderApp('/folders/7?sel=10388', routes(document({ ...letter, canOpen: false })))
    expect((await button('Open')).hasAttribute('disabled')).toBe(true)
    expect((await button('Download')).hasAttribute('disabled')).toBe(true)
  })
})

describe('Check Out, Check In and Release in the details', () => {
  const me = { id: 5, name: 'Priya Nair' }
  const held = (over: Partial<NonNullable<typeof letter.hold>> = {}) => ({ kind: 'checkout' as const, by: me, since: '2026-11-03T10:00:00Z', device: null, heldOn: null, ...over })
  // The hold's own section, above the tabs (DetailsPanel.test.tsx has its rows)
  const info = async (name = 'Check-Out Info') => within(await details()).findByRole('group', { name })
  const value = (section: HTMLElement, label: string) => within(section).getByText(label).nextElementSibling?.textContent

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

    expect(value(await info(), 'User')).toBe('Priya Nair (You)')
    expect(api.callsTo('POST /v1/documents/10388/checkout')).toHaveLength(1)
    expect(within(await details()).queryByRole('button', { name: 'Check Out' })).toBeNull()
    expect(within(await details()).getByRole('button', { name: 'Check In' })).toBeTruthy()
    expect(within(await details()).getByRole('button', { name: 'Release' })).toBeTruthy()
  })

  it("shows who has someone else's check-out, and offers no Check Out or take-over", async () => {
    const theirs = document({ ...letter, canEdit: false, hold: held({ by: { id: 9, name: 'Alex Marsh' } }) })
    await renderApp('/folders/7?sel=10388', routes(theirs))

    expect(value(await info(), 'User')).toBe('Alex Marsh')
    const panel = await details()
    expect(within(panel).queryByRole('button', { name: /Check Out|Check In|Release/ })).toBeNull()
  })

  // Where it's released isn't said any more (the owner's dogfooding punch list, 2026-11-14): the section says where it's held.
  // Release… releases it from here, after a warning (Checked Out to Me, spec 5.3; Release.test.tsx)
  it('shows your check-out on a computer, and offers only Release…, with no words on where to release it', async () => {
    const onMac = { name: 'Healdan Helper on MacBook', app: 'Word' }
    const mine = document({ ...letter, canEdit: false, hold: held({ device: { id: 'd1', ...onMac }, heldOn: onMac }) })
    await renderApp('/folders/7?sel=10388', routes(mine))

    expect(value(await info(), 'Computer')).toBe('Healdan Helper on MacBook')
    const panel = await details()
    expect(within(panel).queryByText(/Release it|released on|Classic Interface \(Unlock\)/)).toBeNull()
    expect(within(panel).getAllByRole('button', { name: /Check Out|Check In|Release/ }).map((button) => button.textContent)).toEqual(['Release…'])
  })

  it('says a lock you took yourself is yours, as the check-out does', async () => {
    const mine = document({ ...letter, canEdit: false, hold: held({ kind: 'lock' }) })
    await renderApp('/folders/7?sel=10388', routes(mine))
    expect(value(await info('Lock Info'), 'User')).toBe('Priya Nair (You)')
  })

  it('offers only files of the document\'s kind to check in, and says so when the server refuses another', async () => {
    const mine = document({ ...letter, hold: held() })
    const refused = problem(422, 'validation')
    const body = { code: 'validation', fields: [{ field: 'file', code: 'extension', detail: 'English from the server' }] }
    await renderApp('/folders/7?sel=10388', routes(mine, { 'POST /v1/documents/10388/versions': Object.assign(refused, { body }) }))
    const input = (await details()).querySelector<HTMLInputElement>('input[type="file"]')!
    expect(input.accept).toBe('.pdf')
    await userEvent.upload(input, new File(['x'], 'other.pdf', { type: 'application/pdf' }), { applyAccept: false })

    expect(await screen.findByText('The file has to be the same kind as the document, with the same extension.')).toBeTruthy()
    expect(screen.queryByText('Some details need fixing.')).toBeNull()
  })

  it("shows who has a lock, and offers no Check Out, without words on where it's undone", async () => {
    const locked = document({ ...letter, canEdit: false, hold: held({ kind: 'lock', by: { id: 9, name: 'Alex Marsh' } }) })
    await renderApp('/folders/7?sel=10388', routes(locked))
    expect(value(await info('Lock Info'), 'User')).toBe('Alex Marsh')
    expect(within(await details()).queryByText(/Unlock it in the Classic Interface/)).toBeNull()
    expect(within(await details()).queryByRole('button', { name: /Check Out|Check In|Release/ })).toBeNull()
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

    await waitFor(async () => expect(within(await details()).queryByRole('group', { name: 'Check-Out Info' })).toBeNull())
    expect(api.callsTo('DELETE /v1/documents/10388/checkout')).toHaveLength(1)
    expect(await button('Check Out')).toBeTruthy()
  })

  it('keeps Release focusable while it works, as Chromium moves the focus off a disabled button, and takes no second press', async () => {
    let answer: (value: ReturnType<typeof document>) => void = () => {}
    const { api } = await renderApp(
      '/folders/7?sel=10388',
      routes(document({ ...letter, hold: held() }), { 'DELETE /v1/documents/10388/checkout': () => new Promise((resolve) => (answer = resolve)) }),
    )
    const release = (await button('Release')) as HTMLButtonElement
    release.focus()
    await userEvent.keyboard('{Enter}')

    await waitFor(() => expect(release.getAttribute('aria-disabled')).toBe('true'))
    expect(release.disabled).toBe(false)
    expect(globalThis.document.activeElement).toBe(release)
    await userEvent.keyboard('{Enter}')
    expect(api.callsTo('DELETE /v1/documents/10388/checkout')).toHaveLength(1)
    await act(async () => answer(document({ ...letter, hold: null })))
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

  it("has a filed email's message read again when it shows the checked-in document as an attachment's copy, and not for a check-out", async () => {
    let doc = document({ ...letter, hold: null })
    const { queryClient } = await renderApp(
      '/folders/7?sel=10388',
      routes(doc, {
        'GET /v1/documents/10388': () => doc,
        'POST /v1/documents/10388/checkout': () => (doc = document({ ...letter, hold: held() })),
        'POST /v1/documents/10388/versions': () => (doc = document({ ...letter, version: '2.2', hold: null })),
      }),
    )
    const showsLetter = cacheMessage(queryClient, 10500, 10388)
    const showsOther = cacheMessage(queryClient, 10501, 10999)
    await userEvent.click(await button('Check Out'))
    await button('Release')
    expect(queryClient.getQueryState(showsLetter)?.isInvalidated).toBe(false)

    const input = (await details()).querySelector<HTMLInputElement>('input[type="file"]')!
    await userEvent.upload(input, new File(['new text'], 'whatever.pdf', { type: 'application/pdf' }))
    await waitFor(() => expect(queryClient.getQueryState(showsLetter)?.isInvalidated).toBe(true))
    expect(queryClient.getQueryState(showsOther)?.isInvalidated).toBe(false)
  })

  it("shows the server's problem from the catalogue when a check-out is refused, and asks again for the hold", async () => {
    const { api } = await renderApp('/folders/7?sel=10388', routes(letter, { 'POST /v1/documents/10388/checkout': problem(409, 'checked-out') }))
    await userEvent.click(await button('Check Out'))

    expect(await screen.findByText("It's checked out.")).toBeTruthy()
    expect(screen.queryByText(/A server sentence/)).toBeNull()
    await waitFor(() => expect(api.callsTo('GET /v1/documents/10388').length).toBeGreaterThan(1))
  })

  it('keeps Preview on each version for a person who may not download, and disables only its Download', async () => {
    await renderApp('/folders/7?sel=10388', routes(document({ ...letter, canOpen: false })))
    const panel = await details()
    await userEvent.click(await within(panel).findByRole('tab', { name: /^Versions/ }))
    expect(((await within(panel).findByRole('button', { name: 'Preview Version 2.1' })) as HTMLButtonElement).disabled).toBe(false)
    expect((within(panel).getByRole('button', { name: 'Download Version 2.1' }) as HTMLButtonElement).disabled).toBe(true)
  })

  it('disables Check Out when the person may not write the document', async () => {
    await renderApp('/folders/7?sel=10388', routes(document({ ...letter, canEdit: false })))
    expect((await button('Check Out')).hasAttribute('disabled')).toBe(true)
  })

  it('passes axe with a hold shown', async () => {
    const theirs = document({ ...letter, canEdit: false, hold: held({ by: { id: 9, name: 'Alex Marsh' } }) })
    await renderApp('/folders/7?sel=10388', routes(theirs))
    await info()
    await expectNoAxeViolations(await details())
  })
})

