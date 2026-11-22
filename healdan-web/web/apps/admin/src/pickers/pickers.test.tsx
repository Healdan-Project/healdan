// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { QueryClientProvider } from '@tanstack/react-query'
import { act, render, renderHook, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState, type ReactNode } from 'react'
import { describe, expect, it, vi } from 'vitest'
import type { adminComponents } from '@healdan/api'
import { createQueryClient, createSignedOut, ServicesProvider } from '@healdan/shell'
import { expectNoAxeViolations } from '../test/axe'
import { brightwell, clientList, clientOf, fakeApi, findPeople, helen, peopleRoutes, saltmarsh, sally, sallyRow, tom, type FakeApi, type Routes } from '../test/fakeApi'
import { ClientPicker } from './ClientPicker'
import { PEOPLE_SEARCH_LIMIT, PersonPicker, type PersonAccounts } from './PersonPicker'
import { TYPING_DELAY_MS } from './typing'
import { CLIENT_SEARCH_LIMIT, useClientSearch } from './useClientSearch'

type A = adminComponents['schemas']

/** Long enough for the typing to pause and the answer to come */
const pause = () => act(() => new Promise((resolve) => setTimeout(resolve, TYPING_DELAY_MS + 100)))

function providers(api: FakeApi) {
  const queryClient = createQueryClient({ retry: false })
  return function Providers({ children }: { children: ReactNode }) {
    return (
      <ServicesProvider api={api} signedOut={createSignedOut()}>
        <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
      </ServicesProvider>
    )
  }
}

function renderWith(ui: ReactNode, routes: Routes = {}) {
  const api = fakeApi(routes)
  const Providers = providers(api)
  return { api, view: render(<Providers>{ui}</Providers>) }
}

// Clients whose number starts with, or name holds, what's asked, as the server answers
const clientRoutes: Routes = {
  'GET /v1/admin/clients': ({ query }: { query?: Record<string, unknown> }) => {
    const q = String(query?.q ?? '').toLowerCase()
    return clientList([saltmarsh, brightwell].filter((one) => String(one.number).startsWith(q) || one.name.toLowerCase().includes(q)).map((one) => clientOf(one)))
  },
}

const box = (name: string) => screen.getByRole('combobox', { name }) as HTMLInputElement

describe('useClientSearch', () => {
  it('asks for nothing while nothing is typed, then for the first clients by number once the typing pauses', async () => {
    const api = fakeApi(clientRoutes)
    const { result, rerender } = renderHook(({ text }) => useClientSearch(text), { wrapper: providers(api), initialProps: { text: '  ' } })
    await pause()
    expect(api.callsTo('GET /v1/admin/clients')).toEqual([])
    expect(result.current.clients).toEqual([])

    rerender({ text: ' 100 ' })
    rerender({ text: ' 1002 ' })
    await waitFor(() => expect(result.current.clients.map((one) => one.name)).toEqual(['Brightwell Bakeries Ltd']))
    // One question, for the words once they settled, trimmed
    expect(api.callsTo('GET /v1/admin/clients').map((call) => call.query)).toEqual([{ q: '1002', limit: CLIENT_SEARCH_LIMIT }])
  })

  it('sends at most 100 characters', async () => {
    const api = fakeApi(clientRoutes)
    renderHook(() => useClientSearch('x'.repeat(150)), { wrapper: providers(api) })
    await waitFor(() => expect(api.callsTo('GET /v1/admin/clients')).toHaveLength(1))
    expect(api.callsTo('GET /v1/admin/clients')[0]?.query?.q).toBe('x'.repeat(100))
  })
})

function ClientExample({ onChange = vi.fn(), start = null }: { onChange?: (client: A['ClientBrief'] | null) => void; start?: A['ClientBrief'] | null }) {
  const [value, setValue] = useState<A['ClientBrief'] | null>(start)
  return (
    <ClientPicker
      value={value}
      onChange={(next) => {
        setValue(next)
        onChange(next)
      }}
    />
  )
}

describe('ClientPicker', () => {
  it('is a search: it finds clients by number or name once the typing pauses, each as its number and name, and picks one', async () => {
    const onChange = vi.fn()
    const { view } = renderWith(<ClientExample onChange={onChange} />, clientRoutes)
    await userEvent.type(box('Client'), 'bake')
    await pause()
    // The list opens by itself once what was found arrives
    const list = await screen.findByRole('listbox')
    expect(within(list).getAllByRole('option').map((one) => one.textContent)).toEqual(['1002 – Brightwell Bakeries Ltd'])
    await expectNoAxeViolations(view.container)
    // The list opened with no option focused, so the key moves to the first
    await userEvent.keyboard('{ArrowDown}{Enter}')
    // The client itself, as other objects name one
    expect(onChange).toHaveBeenLastCalledWith({ id: 2, number: 1002, name: 'Brightwell Bakeries Ltd' })
    expect(box('Client').value).toBe('1002 – Brightwell Bakeries Ltd')
  })

  it('shows the client it holds, and picks nothing once emptied', async () => {
    const onChange = vi.fn()
    renderWith(<ClientExample start={saltmarsh} onChange={onChange} />, clientRoutes)
    expect(box('Client').value).toBe('1001 – Saltmarsh Shipping Ltd')
    await userEvent.clear(box('Client'))
    await userEvent.tab()
    expect(onChange).toHaveBeenLastCalledWith(null)
  })
})

function PersonExample({ accounts, onChange = vi.fn(), start = null }: { accounts: PersonAccounts; onChange?: (person: A['PersonBrief'] | null) => void; start?: A['PersonBrief'] | null }) {
  const [value, setValue] = useState<A['PersonBrief'] | null>(start)
  return (
    <PersonPicker
      label="Owner"
      accounts={accounts}
      value={value}
      onChange={(next) => {
        setValue(next)
        onChange(next)
      }}
    />
  )
}

// The admin find, answering people only, those who have left too
const findRoutes: Routes = { 'GET /v1/admin/find': ({ query }: { query?: Record<string, unknown> }) => findPeople(query?.q) }

/** The options the list offers, once it has opened by itself after the typing has paused */
async function offered() {
  await pause()
  const list = await screen.findByRole('listbox')
  return { list, names: within(list).getAllByRole('option').map((one) => one.textContent) }
}

describe('PersonPicker', () => {
  it('for a field that makes someone an owner, offers only people who can be one: GET /v1/users, enabled accounts, by name with the username', async () => {
    const onChange = vi.fn()
    const { api, view } = renderWith(<PersonExample accounts="enabled" onChange={onChange} />, findRoutes)
    await userEvent.type(box('Owner'), 'ree')
    const { list, names } = await offered()
    expect(names).toEqual(['Tom Reeve tom'])
    expect(api.callsTo('GET /v1/users').at(-1)?.query).toEqual({ q: 'ree' })
    expect(api.callsTo('GET /v1/admin/find')).toEqual([])
    await expectNoAxeViolations(view.container)
    await userEvent.click(within(list).getByRole('option', { name: 'Tom Reeve tom' }))
    expect(onChange).toHaveBeenLastCalledWith(tom)
    expect(box('Owner').value).toBe('Tom Reeve')
  })

  it('there, never offers someone who has left', async () => {
    const { api } = renderWith(<PersonExample accounts="enabled" />, findRoutes)
    await userEvent.type(box('Owner'), 'old')
    await pause()
    expect(api.callsTo('GET /v1/users').at(-1)?.query).toEqual({ q: 'old' })
    expect(screen.queryByRole('listbox')).toBeNull()
  })

  it('for a filter, offers people who have left too, from the admin find, saying so beside their username', async () => {
    const onChange = vi.fn()
    const { api, view } = renderWith(<PersonExample accounts="all" onChange={onChange} />, findRoutes)
    await userEvent.type(box('Owner'), 'o')
    const { list, names } = await offered()
    expect(names).toEqual(['Helen Ashcombe helen', 'Tom Reeve tom', 'Sam Okafor sam', 'Sally Oldham sally.old · Disabled'])
    expect(api.callsTo('GET /v1/admin/find').at(-1)?.query).toEqual({ q: 'o' })
    expect(api.callsTo('GET /v1/users')).toEqual([])
    await expectNoAxeViolations(view.container)
    await userEvent.click(within(list).getByRole('option', { name: 'Sally Oldham sally.old · Disabled' }))
    // The person, as other objects name one
    expect(onChange).toHaveBeenLastCalledWith({ id: sally.id, username: sally.username, name: sally.name })
    expect(box('Owner').value).toBe('Sally Oldham')
  })

  it("for a filter whose person may be one of many, searches the Users list, so more than the find's five come, those who have left too, saying so (P5-R23)", async () => {
    const onChange = vi.fn()
    const { api, view } = renderWith(<PersonExample accounts="users" onChange={onChange} />, { ...peopleRoutes(), ...findRoutes })
    // Every email holds it, so every person matches
    await userEvent.type(box('Owner'), 'e')
    const { list, names } = await offered()
    expect(names).toEqual([
      'Alex Morgan admin',
      'Helen Ashcombe helen',
      'Jamie Clarke jclarke',
      'Priya Nair priya',
      'Ronnie Hale rhale',
      'Sally Oldham sally.old · Disabled',
      'Sam Okafor sam',
    ])
    expect(api.callsTo('GET /v1/admin/users').map((call) => call.query)).toEqual([{ q: 'e', limit: PEOPLE_SEARCH_LIMIT }])
    // Neither of the other sources is asked
    expect(api.callsTo('GET /v1/admin/find')).toEqual([])
    expect(api.callsTo('GET /v1/users')).toEqual([])
    await expectNoAxeViolations(view.container)
    await userEvent.click(within(list).getByRole('option', { name: 'Sally Oldham sally.old · Disabled' }))
    expect(onChange).toHaveBeenLastCalledWith({ id: sallyRow.id, username: sallyRow.username, name: sallyRow.name })
    expect(box('Owner').value).toBe('Sally Oldham')
  })

  it('shows the person it holds by name', () => {
    renderWith(<PersonExample accounts="enabled" start={helen} />)
    expect(box('Owner').value).toBe('Helen Ashcombe')
  })
})
