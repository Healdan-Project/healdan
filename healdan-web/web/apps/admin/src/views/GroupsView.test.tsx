// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { ApiError, type adminComponents } from '@healdan/api'
import { TYPING_DELAY_MS } from '../pickers/typing'
import { expectNoAxeViolations } from '../test/axe'
import { group, groupDetailOf, groupList, groupRoutes, groupRows, partners, problem, type Routes } from '../test/fakeApi'
import { bringObservedIntoView } from '../test/intersection'
import { renderApp } from '../test/renderApp'

type A = adminComponents['schemas']
type Sent = { body?: unknown }

const table = () => screen.getByRole('grid', { name: 'Groups' })
const rows = () => within(table()).getAllByRole('row').slice(1)
const names = () => rows().map((row) => within(row).getAllByRole('rowheader')[0]?.textContent)
const cells = (name: string) => {
  const row = rows().find((one) => within(one).getAllByRole('rowheader')[0]?.textContent?.startsWith(name))
  return within(row!).getAllByRole('gridcell').map((cell) => cell.textContent)
}
const pause = () => act(() => new Promise((resolve) => setTimeout(resolve, TYPING_DELAY_MS + 100)))
const queries = (api: { callsTo: (pattern: string) => { query?: Record<string, unknown> }[] }) => api.callsTo('GET /v1/admin/groups').map((call) => call.query)
const isDisabled = (button: HTMLElement) => button.getAttribute('disabled') !== null

/** A 422 that names a field, as the server gives one */
function invalid(field: string, code: string): ApiError {
  return new ApiError(422, 'validation', 'x', { status: 422, code: 'validation', fields: [{ field, code, message: 'x' }] })
}

async function openGroups(path = '/groups', over: Routes = {}) {
  const rendered = await renderApp(path, { ...groupRoutes(), ...over })
  await within(await screen.findByRole('grid', { name: 'Groups' })).findAllByRole('rowheader')
  return rendered
}

/** The Groups list, with New Group open over it */
async function openNewGroup(over: Routes = {}) {
  const rendered = await openGroups('/groups', over)
  await userEvent.click(screen.getByRole('button', { name: 'New Group' }))
  return { ...rendered, dialog: await screen.findByRole('dialog', { name: 'New Group' }) }
}

describe('The Groups list', () => {
  it('lists every group by name, with its description and members, marking the fixed groups and those a sign-in provider maps', async () => {
    const { api, view } = await openGroups()
    expect(names()).toEqual(['admin Fixed', 'Everyone Fixed', 'Fee Earners Mapped by Authentik', 'guest Fixed', 'Partners', 'publisher Fixed', 'Trainees'])
    expect(cells('Everyone')).toEqual(['Everyone in the firm', '6'])
    expect(cells('Partners')).toEqual(['', '1'])
    expect(cells('publisher')).toEqual(['', '0'])
    expect(screen.getByText('7 Groups')).toBeTruthy()
    expect(queries(api)).toEqual([{ offset: 0, limit: 100 }])
    await expectNoAxeViolations(view.container)
  })

  it('keeps the groups whose name holds the words typed, once the typing pauses, the words in the address', async () => {
    const { api, router } = await openGroups()
    await userEvent.type(screen.getByRole('textbox', { name: 'Name' }), ' ar ')
    await pause()
    await waitFor(() => expect(router.state.location.search).toEqual({ q: 'ar' }))
    await waitFor(() => expect(queries(api).at(-1)).toEqual({ q: 'ar', offset: 0, limit: 100 }))
    await waitFor(() => expect(names()).toEqual(['Fee Earners Mapped by Authentik', 'Partners']))
    await userEvent.clear(screen.getByRole('textbox', { name: 'Name' }))
    await userEvent.type(screen.getByRole('textbox', { name: 'Name' }), 'nobody')
    await pause()
    expect(await screen.findByText('No group matches these words.')).toBeTruthy()
  })

  it('reads its words from the address, and ignores what it can\'t read', async () => {
    const { api } = await openGroups('/groups?q=fee&sort=nope')
    await waitFor(() => expect(queries(api).at(-1)).toEqual({ q: 'fee', offset: 0, limit: 100 }))
    expect(names()).toEqual(['Fee Earners Mapped by Authentik'])
    expect((screen.getByRole('textbox', { name: 'Name' }) as HTMLInputElement).value).toBe('fee')
  })

  it('asks for the next 100 groups as the end of the list comes into view', async () => {
    const many = Array.from({ length: 150 }, (_, i) => group(1000 + i, `Team ${String(i + 1).padStart(3, '0')}`, null))
    const { api } = await openGroups('/groups', { 'GET /v1/admin/groups': ({ query }: { query?: Record<string, unknown> }) => groupList(query, many) })
    act(() => bringObservedIntoView())
    expect(await within(table()).findByRole('rowheader', { name: 'Team 150' })).toBeTruthy()
    expect(queries(api).map((query) => [query?.offset, query?.limit])).toEqual([
      [0, 100],
      [100, 100],
    ])
  })

  it("opens a group's page from its row", async () => {
    const { router } = await openGroups()
    await userEvent.click(within(table()).getByRole('rowheader', { name: 'Partners' }))
    await waitFor(() => expect(router.state.location.pathname).toBe(`/groups/${partners.id}`))
  })

  it("keeps the rows it has while a new read fails, and says it couldn't refresh (P4-R15)", async () => {
    let failing = false
    await openGroups('/groups', { 'GET /v1/admin/groups': ({ query }: { query?: Record<string, unknown> }) => (failing ? problem(500, 'server-error') : groupList(query)) })
    failing = true
    await act(async () => void window.dispatchEvent(new Event('focus')))
    expect(await screen.findByText("Couldn't refresh this, so it shows what was read last.")).toBeTruthy()
    expect(names()).toHaveLength(groupRows.length)
    failing = false
    await userEvent.click(screen.getByRole('button', { name: 'Try Again' }))
    await waitFor(() => expect(screen.queryByText("Couldn't refresh this, so it shows what was read last.")).toBeNull())
  })

  it('says why when the first read fails', async () => {
    await renderApp('/groups', { ...groupRoutes(), 'GET /v1/admin/groups': problem(500, 'server-error') })
    expect(await screen.findByText('Something went wrong.')).toBeTruthy()
    expect(screen.queryByRole('grid', { name: 'Groups' })).toBeNull()
  })
})

describe('New Group', () => {
  it("makes a group with its name and description, then opens the group's page", async () => {
    let sent: unknown
    const made = groupDetailOf(group(30, 'Litigation Team', 'Contentious work'))
    const { router, view, dialog } = await openNewGroup({
      'POST /v1/admin/groups': ({ body }: Sent) => {
        sent = body
        return made
      },
      'GET /v1/admin/groups/30': made,
    })
    const create = within(dialog).getByRole('button', { name: 'Create Group' })
    // A group needs a name
    expect(isDisabled(create)).toBe(true)
    await userEvent.type(within(dialog).getByRole('textbox', { name: 'Name' }), '  Litigation Team ')
    await userEvent.type(within(dialog).getByRole('textbox', { name: 'Description' }), 'Contentious work')
    await pause()
    await expectNoAxeViolations(view.baseElement)
    await userEvent.click(create)
    await waitFor(() => expect(router.state.location.pathname).toBe('/groups/30'))
    expect(sent).toEqual({ name: 'Litigation Team', description: 'Contentious work' } satisfies A['NewGroup'])
    expect(screen.queryByRole('dialog', { name: 'New Group' })).toBeNull()
    expect(await screen.findByText('Created Litigation Team')).toBeTruthy()
  })

  it('sends no description for none', async () => {
    let sent: unknown
    const { dialog } = await openNewGroup({
      'POST /v1/admin/groups': ({ body }: Sent) => {
        sent = body
        return groupDetailOf(group(31, 'Conveyancing', null))
      },
    })
    await userEvent.type(within(dialog).getByRole('textbox', { name: 'Name' }), 'Conveyancing')
    await userEvent.type(within(dialog).getByRole('textbox', { name: 'Description' }), '   ')
    await pause()
    await userEvent.click(within(dialog).getByRole('button', { name: 'Create Group' }))
    await waitFor(() => expect(sent).toEqual({ name: 'Conveyancing', description: null }))
  })

  it("says before the button that another group has the name, in any case, or that the name can't be kept", async () => {
    const { dialog } = await openNewGroup()
    const name = within(dialog).getByRole('textbox', { name: 'Name' })
    const create = within(dialog).getByRole('button', { name: 'Create Group' })
    await userEvent.type(name, 'PARTNERS')
    await pause()
    expect(await within(dialog).findByText("There's already a group called Partners.")).toBeTruthy()
    expect(isDisabled(create)).toBe(true)
    await userEvent.clear(name)
    await userEvent.type(name, '_user_12')
    expect(within(dialog).getByText("A group's name can't start with _user_, as a person's own group's does.")).toBeTruthy()
    expect(isDisabled(create)).toBe(true)
    await userEvent.clear(name)
    await userEvent.type(name, 'x'.repeat(201))
    expect(within(dialog).getByText("A group's name can't be longer than 200 characters.")).toBeTruthy()
    expect(isDisabled(create)).toBe(true)
  })

  it("shows the server's refusal of a field beside it, and any other above the buttons, keeping what was typed", async () => {
    let answer: unknown = invalid('name', 'taken')
    const { dialog } = await openNewGroup({ 'POST /v1/admin/groups': () => answer })
    await userEvent.type(within(dialog).getByRole('textbox', { name: 'Name' }), 'Old Team.12')
    await pause()
    await userEvent.click(within(dialog).getByRole('button', { name: 'Create Group' }))
    expect(await within(dialog).findByText('Another group has this name.')).toBeTruthy()
    expect((within(dialog).getByRole('textbox', { name: 'Name' }) as HTMLInputElement).value).toBe('Old Team.12')

    answer = invalid('description', 'too-long')
    await userEvent.type(within(dialog).getByRole('textbox', { name: 'Name' }), 'x')
    await pause()
    await userEvent.click(within(dialog).getByRole('button', { name: 'Create Group' }))
    expect(await within(dialog).findByText("A description can't be longer than 255 characters.")).toBeTruthy()

    answer = problem(409, 'refused', 'A rule of the firm refuses it.')
    await userEvent.type(within(dialog).getByRole('textbox', { name: 'Name' }), 'y')
    await pause()
    await userEvent.click(within(dialog).getByRole('button', { name: 'Create Group' }))
    expect((await within(dialog).findByRole('alert')).textContent).toBe('A rule of the firm refuses it.')
  })
})
