// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { act, fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { formatDateTime } from '@healdan/ui'
import { TYPING_DELAY_MS } from '../pickers/typing'
import { expectNoAxeViolations } from '../test/axe'
import { adminRow, feeEarners, groupList, helenRow, peopleRoutes, problem, publisherGroup, userList, userRow, userRows, type Routes } from '../test/fakeApi'
import { bringObservedIntoView } from '../test/intersection'
import { renderApp } from '../test/renderApp'

const table = () => screen.getByRole('grid', { name: 'Users' })
const rows = () => within(table()).getAllByRole('row').slice(1)
const names = () => rows().map((row) => within(row).getAllByRole('rowheader')[0]?.textContent)
const cells = (name: string) => {
  const row = rows().find((one) => within(one).getAllByRole('rowheader')[0]?.textContent?.startsWith(name))
  return within(row!).getAllByRole('gridcell').map((cell) => cell.textContent)
}
const pause = () => act(() => new Promise((resolve) => setTimeout(resolve, TYPING_DELAY_MS + 100)))
const queries = (api: { callsTo: (pattern: string) => { query?: Record<string, unknown> }[] }) => api.callsTo('GET /v1/admin/users').map((call) => call.query)

async function openUsers(path = '/users', over: Routes = {}) {
  const rendered = await renderApp(path, { ...peopleRoutes(), ...over })
  await within(await screen.findByRole('grid', { name: 'Users' })).findAllByRole('rowheader')
  return rendered
}

describe('The Users list', () => {
  it('lists every person by name, with their username, groups, how they sign in, status, last sign-in and devices', async () => {
    const { api, view } = await openUsers()
    expect(names()).toEqual(['Alex Morgan', 'Helen Ashcombe', 'Jamie Clarke', 'Priya Nair', 'Ronnie Hale Read-Only', 'Sally Oldham', 'Sam Okafor'])
    // Everyone, which every account but a read-only one is in, isn't repeated on each row
    expect(cells('Helen Ashcombe')).toEqual(['helen', 'Fee Earners, Partners', 'Authentik', 'Active', formatDateTime(helenRow.lastSignIn!), '2'])
    expect(cells('Priya Nair')).toEqual(['priya', 'Trainees', 'Password', 'Active', formatDateTime('2026-11-19T10:39:00Z'), '1'])
    expect(cells('Jamie Clarke')[3]).toBe('Blocked')
    expect(cells('Sally Oldham')[3]).toBe('Disabled')
    expect(cells('Ronnie Hale')).toEqual(['rhale', 'guest', 'Password', 'Active', 'Never', '0'])
    expect(cells('Sam Okafor')[1]).toBe('admin')
    expect(screen.getByText('7 People')).toBeTruthy()
    expect(queries(api)).toEqual([{ offset: 0, limit: 100 }])
    await expectNoAxeViolations(view.container)
  })

  it("sorts by name, username or last sign-in, the order in the address and the server's", async () => {
    const { api, router } = await openUsers()
    await userEvent.click(within(table()).getByRole('columnheader', { name: /Username/ }))
    await waitFor(() => expect(router.state.location.search).toEqual({ sort: 'username', order: 'asc' }))
    await waitFor(() => expect(queries(api).at(-1)).toEqual({ sort: 'username', order: 'asc', offset: 0, limit: 100 }))
    // A date sorts newest first at first
    await userEvent.click(within(table()).getByRole('columnheader', { name: /Last Sign-In/ }))
    await waitFor(() => expect(router.state.location.search).toEqual({ sort: 'lastSignIn', order: 'desc' }))
    await waitFor(() => expect(names()[0]).toBe('Alex Morgan'))
    // Someone who never signed in comes last
    expect(names().at(-1)).toBe('Ronnie Hale Read-Only')
    // Groups and the rest aren't sorted by the server
    expect(within(table()).getByRole('columnheader', { name: 'Groups' }).getAttribute('aria-sort')).toBeNull()
  })

  it('filters by the words typed once the typing pauses, by status and by group, and Clear Filters takes them all off', async () => {
    const { api, router } = await openUsers()
    await userEvent.type(screen.getByRole('textbox', { name: 'Name, Username or Email' }), '  example ')
    await pause()
    await waitFor(() => expect(router.state.location.search).toEqual({ q: 'example' }))
    await waitFor(() => expect(queries(api).at(-1)).toEqual({ q: 'example', offset: 0, limit: 100 }))

    fireEvent.change(screen.getByRole('combobox', { name: 'Status' }), { target: { value: 'disabled' } })
    await waitFor(() => expect(router.state.location.search).toEqual({ q: 'example', status: 'disabled' }))
    await waitFor(() => expect(names()).toEqual(['Sally Oldham']))

    // Group is a search, as groups can be many
    const group = screen.getByRole('combobox', { name: 'Group' })
    await userEvent.type(group, 'fee')
    await userEvent.click(await screen.findByRole('option', { name: 'Fee Earners' }))
    await waitFor(() => expect(router.state.location.search).toEqual({ q: 'example', status: 'disabled', group: String(feeEarners.id) }))
    await waitFor(() => expect(queries(api).at(-1)).toEqual({ q: 'example', status: 'disabled', groupId: feeEarners.id, offset: 0, limit: 100 }))
    await waitFor(() => expect(screen.getByText('Nobody matches these filters.')).toBeTruthy())

    await userEvent.click(screen.getByRole('button', { name: 'Clear Filters' }))
    await waitFor(() => expect(router.state.location.search).toEqual({}))
    await waitFor(() => expect(names()).toHaveLength(userRows.length))
    expect((screen.getByRole('textbox', { name: 'Name, Username or Email' }) as HTMLInputElement).value).toBe('')
  })

  it('reads its filters and order from the address, the group by its name as a row names it, and ignores what it can\'t read', async () => {
    const { api } = await openUsers(`/users?group=${feeEarners.id}&status=active&sort=nope&order=sideways&x=1`)
    await waitFor(() => expect(queries(api).at(-1)).toEqual({ groupId: feeEarners.id, status: 'active', offset: 0, limit: 100 }))
    expect(names()).toEqual(['Helen Ashcombe'])
    await waitFor(() => expect((screen.getByRole('combobox', { name: 'Group' }) as HTMLInputElement).value).toBe('Fee Earners'))
    expect((screen.getByRole('combobox', { name: 'Status' }) as HTMLSelectElement).value).toBe('active')
  })

  it("filters by LogicalDOC's publisher, whose id is negative, its id kept in the address both ways", async () => {
    const groups = { 'GET /v1/admin/groups': ({ query }: { query?: Record<string, unknown> }) => groupList(query) }
    const { api, router, view } = await openUsers('/users', groups)
    await userEvent.type(screen.getByRole('combobox', { name: 'Group' }), 'pub')
    await userEvent.click(await screen.findByRole('option', { name: 'publisher' }))
    await waitFor(() => expect(router.state.location.search).toEqual({ group: String(publisherGroup.id) }))
    await waitFor(() => expect(queries(api).at(-1)).toEqual({ groupId: publisherGroup.id, offset: 0, limit: 100 }))
    view.unmount()

    // Read back from the address, named as the server names it
    const again = await openUsers(`/users?group=${publisherGroup.id}`, { ...groups, 'GET /v1/admin/users': ({ query }: { query?: Record<string, unknown> }) => userList(query, [...userRows, userRow({ id: 50, username: 'pubby', name: 'Pat Ubby', groups: [{ id: publisherGroup.id, name: 'publisher' }] })]) })
    await waitFor(() => expect(queries(again.api).at(-1)).toEqual({ groupId: publisherGroup.id, offset: 0, limit: 100 }))
    expect(names()).toEqual(['Pat Ubby'])
    await waitFor(() => expect((screen.getByRole('combobox', { name: 'Group' }) as HTMLInputElement).value).toBe('publisher'))
  })

  it('asks for the next 100 people as the end of the list comes into view', async () => {
    const many = Array.from({ length: 150 }, (_, i) => userRow({ id: 1000 + i, username: `p${i}`, name: `Person ${String(i + 1).padStart(3, '0')}`, groups: [] }))
    const { api } = await openUsers('/users', { 'GET /v1/admin/users': ({ query }: { query?: Record<string, unknown> }) => userList(query, many) })
    act(() => bringObservedIntoView())
    expect(await within(table()).findByRole('rowheader', { name: 'Person 150' })).toBeTruthy()
    expect(queries(api).map((query) => [query?.offset, query?.limit])).toEqual([
      [0, 100],
      [100, 100],
    ])
  })

  it("opens a person's page from their row", async () => {
    const { router } = await openUsers()
    await userEvent.click(within(table()).getByRole('rowheader', { name: 'Helen Ashcombe' }))
    await waitFor(() => expect(router.state.location.pathname).toBe(`/users/${helenRow.id}`))
  })

  it('keeps the rows it has while a new read fails, and says it couldn\'t refresh (P4-R15)', async () => {
    let failing = false
    await openUsers('/users', { 'GET /v1/admin/users': ({ query }: { query?: Record<string, unknown> }) => (failing ? problem(500, 'server-error') : userList(query)) })
    failing = true
    await act(async () => void window.dispatchEvent(new Event('focus')))
    expect(await screen.findByText("Couldn't refresh this, so it shows what was read last.")).toBeTruthy()
    expect(names()).toHaveLength(userRows.length)
    failing = false
    await userEvent.click(screen.getByRole('button', { name: 'Try Again' }))
    await waitFor(() => expect(screen.queryByText("Couldn't refresh this, so it shows what was read last.")).toBeNull())
  })

  it('says why when the first read fails', async () => {
    await renderApp('/users', { ...peopleRoutes(), 'GET /v1/admin/users': problem(500, 'server-error') })
    expect(await screen.findByText('Something went wrong.')).toBeTruthy()
    expect(screen.queryByRole('grid', { name: 'Users' })).toBeNull()
  })

  it("opens New User, and the new person's page once they're added", async () => {
    const created = { ...helenRow, id: 40, username: 'ann', name: 'Ann Lee' }
    const { router } = await openUsers('/users', {
      'POST /v1/admin/users': { user: { ...adminRow, ...created, language: 'en', timeZone: null, created: '2026-11-21T10:00:00Z', passwordChanged: '2026-11-21T10:00:00Z', mustChangePassword: true, checkedOut: 0, mattersOwned: 0, refusals: { disable: null, enable: null, resetPassword: null, leaveAdmin: null } }, temporaryPassword: 'Tq7-mapLe-29', emailed: false, notEmailed: 'not-asked' },
    })
    await userEvent.click(screen.getByRole('button', { name: 'New User' }))
    const dialog = await screen.findByRole('dialog', { name: 'New User' })
    await userEvent.type(within(dialog).getByRole('textbox', { name: 'Username' }), 'ann')
    await userEvent.type(within(dialog).getByRole('textbox', { name: 'First Name' }), 'Ann')
    await userEvent.type(within(dialog).getByRole('textbox', { name: 'Last Name' }), 'Lee')
    await pause()
    await userEvent.click(within(dialog).getByRole('button', { name: 'Create User' }))
    const done = await screen.findByRole('dialog', { name: 'Added Ann Lee' })
    await userEvent.click(within(done).getByRole('button', { name: 'Done' }))
    await waitFor(() => expect(router.state.location.pathname).toBe('/users/40'))
  })
})
