// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { ApiError, type components } from '@healdan/api'
import { heldByJamie } from '../filing/testing'
import { lease, letter as folderLetter, plan, routes as folderRoutes, row, select } from '../organising/testing'
import { expectNoAxeViolations } from '../test/axe'
import { detailsRoutes } from '../test/details'
import { children, document as doc, page, type Routes } from '../test/fakeApi'
import { renderApp } from '../test/renderApp'

type S = components['schemas']

// Release… in Checked Out to Me, the toolbar, a row's menu and Check-Out Info (spec 5.3, 5.4): a check-out from the web page
// is released at once, and one another of the person's computers holds only after the warning, with elsewhere=true.

const priya = { id: 5, name: 'Priya Nair' }
const since = '2026-11-12T09:00:00Z'
const on = (computer: string, app: string): S['HoldView'] => ({
  kind: 'checkout',
  by: priya,
  since,
  device: { id: computer, name: `Healdan Helper on ${computer}`, app },
  heldOn: { name: `Healdan Helper on ${computer}`, app },
})
const inBrowser: S['HoldView'] = { kind: 'checkout', by: priya, since, device: null, heldOn: null }

const letter = doc({ docNumber: 10431, name: 'Letter to Dunmere Pryce – Response to Claim.docx', hold: on('iMac', 'Word') })
const laytime = doc({ docNumber: 10405, name: 'Laytime Calculation – MV Northern Star.xlsx', hold: on('MacBook', 'Excel') })
const report = doc({ docNumber: 10447, name: 'Report on Lease – Unit 4.odt', hold: on('MacBook', 'LibreOffice') })
const grant = doc({ docNumber: 10414, name: 'Grant Agreement – Draft.docx', hold: on('MacBook', 'Word') })
const capTable = doc({ docNumber: 10420, name: 'Cap Table – Series A.xlsx', hold: inBrowser })

/** Checked Out to Me with five check-outs that release as the server would, recording each release and its query; `over` answers first */
function checkouts(over: Routes = {}) {
  let held = [letter, laytime, report, grant, capTable]
  const released: { docNumber: number; query?: Record<string, unknown> }[] = []
  const all: Routes = {
    'GET /v1/me/checkouts': () => page(held),
    'GET /v1/auth/tokens': page([]),
    'DELETE /v1/documents/*/checkout': ({ params, query }: { params: string[]; query?: Record<string, unknown> }) => {
      const docNumber = Number(params[0])
      released.push({ docNumber, query })
      const found = held.find((one) => one.docNumber === docNumber)
      held = held.filter((one) => one.docNumber !== docNumber)
      return found && { ...found, hold: null }
    },
    ...over,
  }
  return { all, released }
}

// A toast is an alert dialog too: the warning is the one whose name asks
const WARNING = /^Release .*\?$/
const warning = (name: string | RegExp = WARNING) => screen.findByRole('alertdialog', { name })
const noWarning = () => expect(screen.queryByRole('alertdialog', { name: WARNING })).toBeNull()
const toolbar = () => screen.findByRole('toolbar', { name: 'Checked Out Actions' })
const toolbarButton = async (name: string) => within(await toolbar()).findByRole('button', { name })
const menuNames = async () => within(await screen.findByRole('menu')).getAllByRole('menuitem').map((item) => item.textContent)

describe('Release', () => {
  it("releases a browser's check-out at once", async () => {
    const { all, released } = checkouts()
    const { api } = await renderApp('/checked-out', all)
    await select(/Cap Table/)
    const asked = api.callsTo('GET /v1/me/checkouts').length
    await userEvent.click(await toolbarButton('Release'))

    await waitFor(() => expect(released).toEqual([{ docNumber: 10420, query: undefined }]))
    expect(api.callsTo('DELETE /v1/documents/10420/checkout')[0]?.query).toBeUndefined()
    noWarning()
    expect(await screen.findByText('Released 1 document')).toBeTruthy()
    await waitFor(() => expect(api.callsTo('GET /v1/me/checkouts').length).toBeGreaterThan(asked))
    await waitFor(() => expect(within(screen.getByRole('main')).queryByRole('row', { name: /Cap Table/ })).toBeNull())
  })

  it("asks first for another computer's", async () => {
    const { all, released } = checkouts()
    const { api } = await renderApp('/checked-out', all)
    await select(/Laytime/)
    await userEvent.click(await toolbarButton('Release…'))
    const dialog = await warning('Release Laytime Calculation – MV Northern Star.xlsx from MacBook?')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }))
    await waitFor(noWarning)
    expect(released).toEqual([])

    await userEvent.click(await toolbarButton('Release…'))
    await userEvent.click(within(await warning()).getByRole('button', { name: 'Release Document' }))
    await waitFor(() => expect(released).toEqual([{ docNumber: 10405, query: { elsewhere: 'true' } }]))
    expect(api.callsTo('DELETE /v1/documents/10405/checkout')[0]?.query).toEqual({ elsewhere: 'true' })
    expect(await screen.findByText('Released 1 document')).toBeTruthy()
  })

  it("sends elsewhere only for the computers' check-outs when a selection mixes them with the browser's", async () => {
    const { all, released } = checkouts()
    await renderApp('/checked-out', all)
    await select(/Report on Lease/, /Cap Table/)
    await userEvent.click(await toolbarButton('Release…'))
    await userEvent.click(within(await warning('Release 3 Documents from MacBook?')).getByRole('button', { name: 'Release 3 Documents' }))
    await waitFor(() => expect(released).toHaveLength(3))
    expect(released).toEqual([
      { docNumber: 10447, query: { elsewhere: 'true' } },
      { docNumber: 10414, query: { elsewhere: 'true' } },
      { docNumber: 10420, query: undefined },
    ])
    expect(await screen.findByText('Released 3 documents')).toBeTruthy()
  })

  it('names what was refused and releases the rest', async () => {
    const { all, released } = checkouts({
      'DELETE /v1/documents/10405/checkout': new ApiError(409, 'checked-out', 'A server sentence that must not be shown raw.', { code: 'checked-out', hold: heldByJamie }),
    })
    await renderApp('/checked-out', all)
    await select(/Laytime/, /Report on Lease/)
    await userEvent.click(await toolbarButton('Release…'))
    await userEvent.click(within(await warning('Release 2 Documents from MacBook?')).getByRole('button', { name: 'Release 2 Documents' }))

    const refusals = await screen.findByRole('dialog', { name: "Some Documents Weren't Released" })
    const lines = within(refusals).getAllByRole('listitem')
    expect(lines).toHaveLength(1)
    expect(lines[0]!.textContent).toMatch(/^Laytime Calculation – MV Northern Star\.xlsx: It's checked out\. Checked out by Jamie Clarke since .*\.$/)
    expect(screen.queryByText(/server sentence/)).toBeNull()
    // The other was released all the same
    expect(released).toEqual([{ docNumber: 10447, query: { elsewhere: 'true' } }])
    expect(await screen.findByText('Released 1 document')).toBeTruthy()
    await expectNoAxeViolations(refusals)
  })

  it('puts the focus on the next row', async () => {
    const { all } = checkouts()
    await renderApp('/checked-out', all)
    await select(/Letter to Dunmere/)
    await userEvent.click(await toolbarButton('Release…'))
    await userEvent.click(within(await warning()).getByRole('button', { name: 'Release Document' }))
    await waitFor(() => expect(globalThis.document.activeElement?.getAttribute('data-row-key')).toBe('d10405'))
  })

  it('leaves the focus where it is when the released rows stay, as in a folder', async () => {
    const mine = doc({ ...plan, hold: inBrowser })
    const { all } = folderRoutes({ 'GET /v1/folders/7/children': children([folderLetter, lease, mine]), 'DELETE /v1/documents/10433/checkout': doc({ ...plan, hold: null }) })
    await renderApp('/folders/7', all)
    fireEvent.contextMenu(await row(/Plan/))
    await userEvent.click(within(await screen.findByRole('menu')).getByRole('menuitem', { name: 'Release' }))
    expect(await screen.findByText('Released 1 document')).toBeTruthy()
    const focused = () => globalThis.document.activeElement?.getAttribute('data-row-key')
    await waitFor(() => expect(focused()).toBe('d10433'))
    // Its row is still drawn, so it isn't taken for gone: no later turn moves the focus to the row before it, as a delete would
    await new Promise((resolve) => setTimeout(resolve, 150))
    expect(focused()).toBe('d10433')
  })

  it('offers Release… in the right-click menu, the toolbar and Check-Out Info', async () => {
    const { all, released } = checkouts(detailsRoutes(laytime))
    await renderApp('/checked-out?sel=10405', all)
    expect(await toolbarButton('Release…')).toBeTruthy()
    expect(within(await toolbar()).queryByRole('button', { name: 'Check Out' })).toBeNull()

    const details = await screen.findByRole('region', { name: 'Details' })
    await userEvent.click(await within(details).findByRole('button', { name: 'Release…' }))
    expect(await warning('Release Laytime Calculation – MV Northern Star.xlsx from MacBook?')).toBeTruthy()
    await userEvent.keyboard('{Escape}')
    await waitFor(noWarning)

    fireEvent.contextMenu(await row(/Laytime/))
    expect(await menuNames()).toEqual(['Open', 'Preview', 'Open in New Tab', 'Download', 'Release…', 'Rename', 'Move To…', 'Delete', 'Copy Link', 'Add to Favorites'])
    await userEvent.click(within(await screen.findByRole('menu')).getByRole('menuitem', { name: 'Release…' }))
    expect(await warning()).toBeTruthy()
    await userEvent.keyboard('{Escape}')
    await waitFor(noWarning)
    expect(released).toEqual([])

    // The browser's own check-out keeps Check In beside a Release that doesn't ask
    fireEvent.contextMenu(await row(/Cap Table/))
    expect(await menuNames()).toEqual(['Open', 'Preview', 'Open in New Tab', 'Download', 'Check In', 'Release', 'Rename', 'Move To…', 'Delete', 'Copy Link', 'Add to Favorites'])
    fireEvent.keyDown(await screen.findByRole('menu'), { key: 'Escape' })
    await waitFor(() => expect(screen.queryByRole('menu')).toBeNull())

    // Several, all checked out to the person: the toolbar and the menu release them all
    await select(/Grant/, /Cap Table/)
    expect(await toolbarButton('Release…')).toBeTruthy()
    fireEvent.contextMenu(await row(/Grant/))
    expect(await screen.findByRole('menu', { name: 'Actions for 2 documents' })).toBeTruthy()
    expect((await menuNames()).filter((name) => /Release|Check/.test(name ?? ''))).toEqual(['Release…'])
  })

  it("offers no Release for someone else's check-out or a lock", async () => {
    const theirs = doc({ docNumber: 10500, name: 'Theirs.docx', hold: heldByJamie })
    const locked = doc({ docNumber: 10501, name: 'Locked.docx', hold: { kind: 'lock', by: priya, since, device: null, heldOn: null } })
    await renderApp('/checked-out', { 'GET /v1/me/checkouts': page([theirs, locked, capTable]) })
    for (const name of [/Theirs/, /Locked/]) {
      await select(name)
      await waitFor(async () => expect(within(await toolbar()).getByRole('button', { name: 'Check Out' }).hasAttribute('disabled')).toBe(true))
      expect(within(await toolbar()).queryByRole('button', { name: /Release/ })).toBeNull()
      fireEvent.contextMenu(await row(name))
      expect((await menuNames()).filter((item) => /Release|Check/.test(item ?? ''))).toEqual(['Check Out'])
      fireEvent.keyDown(await screen.findByRole('menu'), { key: 'Escape' })
      await waitFor(() => expect(screen.queryByRole('menu')).toBeNull())
    }
    // Nor for a selection with one in it, beside the person's own
    await select(/Locked/, /Cap Table/)
    await waitFor(async () => expect(within(await toolbar()).queryByRole('button', { name: /Release/ })).toBeNull())
  })

  it('opens Signed-in Devices from the warning, over it', async () => {
    const { all, released } = checkouts()
    await renderApp('/checked-out', all)
    await select(/Laytime/)
    await userEvent.click(await toolbarButton('Release…'))
    const asking = await warning()
    await userEvent.click(within(asking).getByRole('button', { name: 'Signed-in Devices' }))

    const devices = await screen.findByRole('dialog', { name: 'Signed-in Devices' })
    expect(await within(devices).findByText('No devices are signed in.')).toBeTruthy()
    // The warning is still open, under it
    expect(asking.isConnected).toBe(true)
    expect(asking.compareDocumentPosition(devices) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()

    await userEvent.click(within(devices).getByRole('button', { name: 'Close' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Signed-in Devices' })).toBeNull())
    expect(await warning('Release Laytime Calculation – MV Northern Star.xlsx from MacBook?')).toBeTruthy()
    expect(released).toEqual([])
  })
})
