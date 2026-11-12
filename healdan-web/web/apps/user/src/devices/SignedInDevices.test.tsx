// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import type { components } from '@healdan/api'
import { expectNoAxeViolations } from '../test/axe'
import { page, problem } from '../test/fakeApi'
import { renderApp } from '../test/renderApp'

type Token = components['schemas']['TokenView']

const token = (over: Partial<Token>): Token => ({
  id: 1,
  name: 'Healdan Helper on a MacBook',
  created: '2026-10-20T09:00:00Z',
  lastUsed: '2026-11-09T16:30:00Z',
  expiresAt: '2027-10-20T09:00:00Z',
  current: false,
  ...over,
})

function devices(initial: Token[]) {
  let held = initial
  const revoked: number[] = []
  return {
    revoked,
    routes: {
      'GET /v1/auth/tokens': () => page(held),
      'DELETE /v1/auth/tokens/*': ({ params }: { params: string[] }) => {
        revoked.push(Number(params[0]))
        held = held.filter((one) => one.id !== Number(params[0]))
        return undefined
      },
    },
  }
}

async function openDevices(routes: Record<string, unknown>) {
  const rendered = await renderApp('/', routes)
  const trigger = await screen.findByRole('button', { name: 'Priya Nair' })
  fireEvent.keyDown(trigger, { key: 'Enter' })
  fireEvent.keyUp(trigger, { key: 'Enter' })
  fireEvent.click(await screen.findByRole('menuitem', { name: 'Signed-in Devices' }))
  return { ...rendered, dialog: await screen.findByRole('dialog', { name: 'Signed-in Devices' }) }
}

describe('Signed-in Devices', () => {
  it("lists each device's name, when it signed in, when it was last used and when it expires, and says the web page's sign-in isn't listed", async () => {
    const two = [token({ id: 1 }), token({ id: 2, name: 'Word on the front desk PC', created: '2026-09-01T08:00:00Z', lastUsed: '2026-09-02T08:00:00Z', expiresAt: '2026-12-01T08:00:00Z' })]
    const { dialog } = await openDevices(devices(two).routes)
    const list = await within(dialog).findByRole('table', { name: 'Signed-in Devices' })
    expect(within(list).getAllByRole('columnheader').map((header) => header.textContent)).toEqual(['Device', 'Signed In', 'Last Used', 'Expires', 'Actions'])
    const rows = within(list).getAllByRole('row').slice(1)
    expect(rows).toHaveLength(2)
    expect(within(rows[0]!).getByRole('rowheader').textContent).toBe('Healdan Helper on a MacBook')
    expect(rows[0]!.textContent).toContain('Oct 20, 2026')
    expect(rows[0]!.textContent).toContain('Nov 9, 2026')
    expect(rows[0]!.textContent).toContain('Oct 20, 2027')
    expect(rows[1]!.textContent).toContain('Sep 1, 2026')
    expect(dialog.textContent).toContain("This web page's own sign-in isn't listed: Sign Out ends it.")
    await expectNoAxeViolations(dialog)
  })

  it('shows a name as text, never as markup', async () => {
    const { dialog } = await openDevices(devices([token({ name: '<img src=x onerror=alert(1)> Laptop' })]).routes)
    expect(await within(dialog).findByText('<img src=x onerror=alert(1)> Laptop')).toBeTruthy()
    expect(dialog.querySelector('img')).toBeNull()
  })

  it('says so when no device is signed in', async () => {
    const { dialog } = await openDevices(devices([]).routes)
    expect(await within(dialog).findByText('No devices are signed in.')).toBeTruthy()
    expect(within(dialog).queryByRole('table')).toBeNull()
  })

  it('asks before it revokes: Cancel makes no call, and Revoke removes the device and says so', async () => {
    const held = devices([token({ id: 7 }), token({ id: 8, name: 'Excel add-in' })])
    const { dialog, api } = await openDevices(held.routes)
    fireEvent.click(await within(dialog).findByRole('button', { name: 'Revoke Healdan Helper on a MacBook' }))

    // The question, naming the device and what happens to it
    expect(within(dialog).getByRole('heading', { name: 'Revoke Healdan Helper on a MacBook?' })).toBeTruthy()
    expect(dialog.textContent).toContain('has to ask you to sign in again')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }))
    expect(api.callsTo('DELETE /v1/auth/tokens/*')).toHaveLength(0)
    expect(await within(dialog).findByRole('table', { name: 'Signed-in Devices' })).toBeTruthy()

    fireEvent.click(within(dialog).getByRole('button', { name: 'Revoke Healdan Helper on a MacBook' }))
    await userEvent.click(within(dialog).getByRole('button', { name: 'Revoke' }))
    await waitFor(() => expect(held.revoked).toEqual([7]))
    expect(await screen.findByText('Revoked Healdan Helper on a MacBook')).toBeTruthy()
    // The list is asked for again, and the device is gone from it
    await waitFor(() => expect(within(dialog).queryByRole('rowheader', { name: 'Healdan Helper on a MacBook' })).toBeNull())
    expect(within(dialog).getByRole('rowheader', { name: 'Excel add-in' })).toBeTruthy()
  })

  it("says what went wrong in the catalogue's words and keeps the list when a revoke is refused", async () => {
    const held = devices([token({ id: 7 })])
    const { dialog } = await openDevices({ ...held.routes, 'DELETE /v1/auth/tokens/*': problem(404, 'not-found') })
    fireEvent.click(await within(dialog).findByRole('button', { name: 'Revoke Healdan Helper on a MacBook' }))
    await userEvent.click(within(dialog).getByRole('button', { name: 'Revoke' }))
    expect(await within(dialog).findByRole('alert')).toBeTruthy()
    expect(within(dialog).getByRole('alert').textContent).toBe("Not found, or you can't see it.")
    // Cancel goes back to the list, which still has the device
    await userEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }))
    expect(await within(dialog).findByRole('rowheader', { name: 'Healdan Helper on a MacBook' })).toBeTruthy()
  })

  it('offers Try Again when the list fails to load, and closes with Esc', async () => {
    const { dialog } = await openDevices({ 'GET /v1/auth/tokens': problem(500, 'server-error') })
    expect(await within(dialog).findByText('Something went wrong.')).toBeTruthy()
    expect(within(dialog).getByRole('button', { name: 'Try Again' })).toBeTruthy()
    // The focus is inside the dialog, so Esc reaches it
    await waitFor(() => expect(dialog.contains(document.activeElement)).toBe(true))
    await userEvent.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  })

  describe('where the focus goes when the question gives way to the list', () => {
    const named = (n: number) => `Device ${n}`
    const three = () => devices([token({ id: 1, name: named(1) }), token({ id: 2, name: named(2) }), token({ id: 3, name: named(3) })])
    const revokeOf = (dialog: HTMLElement, n: number) => within(dialog).getByRole('button', { name: `Revoke ${named(n)}` })
    const ask = async (dialog: HTMLElement, n: number) => {
      fireEvent.click(await within(dialog).findByRole('button', { name: `Revoke ${named(n)}` }))
      await within(dialog).findByRole('heading', { name: `Revoke ${named(n)}?` })
    }

    it('goes back to the Revoke of the row that was asked about, after Cancel, inside the dialog', async () => {
      const { dialog } = await openDevices(three().routes)
      await ask(dialog, 2)
      await userEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }))
      await waitFor(() => expect(globalThis.document.activeElement).toBe(revokeOf(dialog, 2)))
      expect(dialog.contains(globalThis.document.activeElement)).toBe(true)
    })

    it("goes to the next row's Revoke after a revoke, the previous one's after the last, and Close with none left", async () => {
      const held = three()
      const { dialog } = await openDevices(held.routes)
      await ask(dialog, 1)
      await userEvent.click(within(dialog).getByRole('button', { name: 'Revoke' }))
      await waitFor(() => expect(globalThis.document.activeElement).toBe(revokeOf(dialog, 2)))

      await ask(dialog, 3)
      await userEvent.click(within(dialog).getByRole('button', { name: 'Revoke' }))
      await waitFor(() => expect(globalThis.document.activeElement).toBe(revokeOf(dialog, 2)))

      await ask(dialog, 2)
      await userEvent.click(within(dialog).getByRole('button', { name: 'Revoke' }))
      await waitFor(() => expect(globalThis.document.activeElement).toBe(within(dialog).getByRole('button', { name: 'Close' })))
    })
  })

  it('asks for the list without a limit, which the route does not take', async () => {
    const { api } = await openDevices(devices([token({})]).routes)
    await waitFor(() => expect(api.callsTo('GET /v1/auth/tokens')).toHaveLength(1))
    expect(api.callsTo('GET /v1/auth/tokens')[0]!.query).toBeUndefined()
  })

  it('sends one DELETE for a double press, with the confirming Revoke disabled while it runs', async () => {
    let release: () => void = () => {}
    const gate = new Promise<void>((resolve) => (release = resolve))
    const held = devices([token({ id: 7 })])
    const { dialog, api } = await openDevices({ ...held.routes, 'DELETE /v1/auth/tokens/*': async () => (await gate, undefined) })
    fireEvent.click(await within(dialog).findByRole('button', { name: 'Revoke Healdan Helper on a MacBook' }))
    const confirm = within(dialog).getByRole('button', { name: 'Revoke' })
    fireEvent.click(confirm)
    await waitFor(() => expect((confirm as HTMLButtonElement).disabled).toBe(true))
    fireEvent.click(confirm)
    release()
    await waitFor(() => expect(within(dialog).queryByRole('heading', { name: /^Revoke / })).toBeNull())
    expect(api.callsTo('DELETE /v1/auth/tokens/*')).toHaveLength(1)
  })

  it('clears a refusal when another question opens', async () => {
    const held = devices([token({ id: 7 }), token({ id: 8, name: 'Excel add-in' })])
    const { dialog } = await openDevices({ ...held.routes, 'DELETE /v1/auth/tokens/7': problem(404, 'not-found') })
    fireEvent.click(await within(dialog).findByRole('button', { name: 'Revoke Healdan Helper on a MacBook' }))
    await userEvent.click(within(dialog).getByRole('button', { name: 'Revoke' }))
    await within(dialog).findByRole('alert')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }))
    fireEvent.click(await within(dialog).findByRole('button', { name: 'Revoke Excel add-in' }))
    await within(dialog).findByRole('heading', { name: 'Revoke Excel add-in?' })
    expect(within(dialog).queryByRole('alert')).toBeNull()
  })
})
