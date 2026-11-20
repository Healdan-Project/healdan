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
    // The list is drawn in React Aria's overlay layer at the end of <body>, outside the page's landmarks by design, so the
    // landmark rule can't hold for it (as @healdan/ui's ComboBox test says)
    const result = await axe.run(document.body, { rules: { 'color-contrast': { enabled: false }, region: { enabled: false } } })
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

  it('opens what is picked with the arrow keys and Enter, where it is managed, and empties the box', async () => {
    await renderApp('/', routes)
    const user = userEvent.setup()
    await user.type(box(), 'sal')
    await screen.findByRole('option', { name: /Sally Oldham/ })
    await user.keyboard('{ArrowDown}{ArrowDown}{Enter}')
    await waitFor(() => expect(goTo).toHaveBeenCalledWith(destinationOf({ kind: 'matter', matter: found.matters[0]! })))
    expect(goTo).toHaveBeenCalledTimes(1)
    expect((box() as HTMLInputElement).value).toBe('')
  })

  it('opens what is clicked', async () => {
    await renderApp('/', routes)
    const user = userEvent.setup()
    await user.type(box(), 'sal')
    await user.click(await screen.findByRole('option', { name: /Priya Nair/ }))
    await waitFor(() => expect(goTo).toHaveBeenCalledWith(destinationOf({ kind: 'person', person: found.people[0]! })))
  })

  it('opens the first thing found on Enter when the arrow keys have picked none', async () => {
    await renderApp('/', routes)
    const user = userEvent.setup()
    await user.type(box(), 'sal')
    await screen.findByRole('option', { name: /Priya Nair/ })
    await user.keyboard('{Enter}')
    await waitFor(() => expect(goTo).toHaveBeenCalledWith(destinationOf({ kind: 'client', client: found.clients[0]! })))
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

// Clients, Matters and Users have no screen of their own yet (spec 14), so each opens the Classic Interface. A matter
// isn't opened in the User app: the find gives its id, and the User app's addresses need its folder's.
describe('Where a find opens each kind', () => {
  it('is the Classic Interface, until each kind\'s screen ships', () => {
    expect(destinationOf({ kind: 'client', client: found.clients[0]! })).toBe('/frontend.jsp')
    expect(destinationOf({ kind: 'matter', matter: found.matters[0]! })).toBe('/frontend.jsp')
    expect(destinationOf({ kind: 'person', person: found.people[0]! })).toBe('/frontend.jsp')
  })
})
