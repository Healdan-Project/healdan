// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import axe from 'axe-core'
import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { goTo } from '@healdan/shell'
import { setLabels } from '@healdan/ui'
import { expectNoAxeViolations } from '../test/axe'
import { found, nothingFound, problem } from '../test/fakeApi'
import { renderApp } from '../test/renderApp'
import { FIND_DELAY_MS, destinationOf } from './FindBox'

// The find leaves the page through the shell's own module, so that's the one a stand-in replaces
vi.mock('@healdan/shell/src/frame/navigate', () => ({ goTo: vi.fn() }))

beforeEach(() => vi.mocked(goTo).mockClear())

const box = () => screen.getByRole('combobox', { name: 'Find a client, matter or person' })
const pause = () => new Promise((resolve) => setTimeout(resolve, FIND_DELAY_MS + 100))
const routes = { 'GET /v1/admin/find': found }

describe('Find a client, matter or person', () => {
  it('finds once the typing pauses: clients, matters and people, each under its heading', async () => {
    const { api } = await renderApp('/', routes)
    const user = userEvent.setup()
    await user.type(box(), 'sal')
    const list = await screen.findByRole('listbox')
    await waitFor(() => expect(within(list).getAllByRole('option')).toHaveLength(4))
    expect(api.callsTo('GET /v1/admin/find').map((call) => call.query)).toEqual([{ q: 'sal' }])

    const section = (name: string) => within(within(list).getByRole('group', { name })).getAllByRole('option').map((option) => option.textContent)
    expect(section('Clients')).toEqual(['1001 – Saltmarsh Shipping Ltd'])
    expect(section('Matters')).toEqual(['1001.1 – Charter DisputeSaltmarsh Shipping Ltd'])
    expect(section('People')).toEqual(['Priya Nairpriya', 'Sally Oldhamsally.old · Disabled'])
    await expectNoAxeViolations(screen.getByRole('banner'))
    // The list is drawn right after the box, inside the find's search landmark (@healdan/ui's ComboBox), so the whole page
    // holds every landmark rule while it's open; jsdom can't work out colours, which the browser specs check
    const result = await axe.run(document.body, { rules: { 'color-contrast': { enabled: false } } })
    expect(result.violations.map((v) => `${v.id}: ${v.nodes.map((n) => n.html).join(' | ')}`)).toEqual([])
  })

  it('says Matters as the firm does', async () => {
    setLabels({ matter: 'Project', matters: 'Projects' })
    await renderApp('/', routes)
    await userEvent.setup().type(box(), 'sal')
    expect(await screen.findByRole('group', { name: 'Projects' })).toBeTruthy()
  })

  it('asks nothing for a single letter, but does for a number, as a client is found by its number', async () => {
    const { api } = await renderApp('/', routes)
    const user = userEvent.setup()
    await user.type(box(), 'a')
    await pause()
    expect(api.callsTo('GET /v1/admin/find')).toEqual([])
    await user.clear(box())
    await user.type(box(), '7')
    await waitFor(() => expect(api.callsTo('GET /v1/admin/find').map((call) => call.query)).toEqual([{ q: '7' }]))
  })

  it('sends what is typed without the spaces at either end, and at most 100 characters', async () => {
    const { api } = await renderApp('/', routes)
    fireEvent.change(box(), { target: { value: '  1001.1  ' } })
    await waitFor(() => expect(api.callsTo('GET /v1/admin/find').map((call) => call.query)).toEqual([{ q: '1001.1' }]))
    fireEvent.change(box(), { target: { value: 'x'.repeat(150) } })
    await waitFor(() => expect(api.callsTo('GET /v1/admin/find')).toHaveLength(2))
    expect((api.callsTo('GET /v1/admin/find')[1]?.query?.q as string).length).toBe(100)
  })

  it("opens what is picked with the arrow keys and Enter, a matter at its page here, inside the app, and empties the box", async () => {
    const { router } = await renderApp('/', routes)
    const user = userEvent.setup()
    await user.type(box(), 'sal')
    await screen.findByRole('option', { name: /Sally Oldham/ })
    await user.keyboard('{ArrowDown}{ArrowDown}{Enter}')
    await waitFor(() => expect(router.state.location.pathname).toBe('/matters/11'))
    expect(goTo).not.toHaveBeenCalled()
    expect((box() as HTMLInputElement).value).toBe('')
  })

  it('opens a person who is clicked at their page here, inside the app (P4-R18)', async () => {
    const { router } = await renderApp('/', routes)
    const user = userEvent.setup()
    await user.type(box(), 'sal')
    await user.click(await screen.findByRole('option', { name: /Priya Nair/ }))
    await waitFor(() => expect(router.state.location.pathname).toBe('/users/7'))
    expect(goTo).not.toHaveBeenCalled()
  })

  it("opens the first thing found on Enter when the arrow keys have picked none: here the client, at its page", async () => {
    const { router } = await renderApp('/', routes)
    const user = userEvent.setup()
    await user.type(box(), 'sal')
    await screen.findByRole('option', { name: /Priya Nair/ })
    await user.keyboard('{Enter}')
    await waitFor(() => expect(router.state.location.pathname).toBe('/clients/1'))
    expect(goTo).not.toHaveBeenCalled()
  })

  it('opens nothing on Enter before anything is found', async () => {
    const { api } = await renderApp('/', routes)
    const user = userEvent.setup()
    await user.type(box(), 'x{Enter}')
    await pause()
    expect(api.callsTo('GET /v1/admin/find')).toEqual([])
    expect(goTo).not.toHaveBeenCalled()
  })

  it('says when nothing was found, and when the find failed', async () => {
    let answer: unknown = nothingFound
    await renderApp('/', { 'GET /v1/admin/find': () => answer })
    const user = userEvent.setup()
    // There before it has words, so a screen reader says them as they come
    const status = within(screen.getByRole('search')).getByRole('status')
    expect(status.textContent).toBe('')
    await user.type(box(), 'zzz')
    await waitFor(() => expect(status.textContent).toBe('Nothing found.'))
    expect(screen.queryByRole('listbox')).toBeNull()
    answer = problem(500, 'server-error')
    await user.type(box(), 'z')
    await waitFor(() => expect(status.textContent).toBe('Something went wrong.'))
    expect(screen.queryByText('A server sentence that must not be shown raw.')).toBeNull()
  })
})

// A client, a matter and a person each open at their page here. A matter isn't opened in the User app: the find gives its
// id, and the User app's addresses need its folder's.
describe('Where a find opens each kind', () => {
  it("is a client's, a matter's or a person's page here, by its id", () => {
    expect(destinationOf({ kind: 'client', client: found.clients[0]! })).toBe('/admin/clients/1')
    expect(destinationOf({ kind: 'matter', matter: found.matters[0]! })).toBe('/admin/matters/11')
    expect(destinationOf({ kind: 'person', person: found.people[0]! })).toBe('/admin/users/7')
  })
})
