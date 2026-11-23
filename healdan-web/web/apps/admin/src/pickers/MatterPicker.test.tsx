// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState, type ReactNode } from 'react'
import { describe, expect, it, vi } from 'vitest'
import type { adminComponents } from '@healdan/api'
import { createQueryClient, createSignedOut, ServicesProvider } from '@healdan/shell'
import { expectNoAxeViolations } from '../test/axe'
import { auditMatters, fakeApi, type Routes } from '../test/fakeApi'
import { MATTER_SEARCH_LIMIT, MatterPicker } from './MatterPicker'
import { TYPING_DELAY_MS } from './typing'

type A = adminComponents['schemas']

/** Long enough for the typing to pause and the answer to come */
const pause = () => act(() => new Promise((resolve) => setTimeout(resolve, TYPING_DELAY_MS + 100)))

function renderWith(ui: ReactNode, routes: Routes = {}) {
  const api = fakeApi(routes)
  const queryClient = createQueryClient({ retry: false })
  const view = render(
    <ServicesProvider api={api} signedOut={createSignedOut()}>
      <QueryClientProvider client={queryClient}>{ui}</QueryClientProvider>
    </ServicesProvider>,
  )
  return { api, view }
}

const box = (name: string) => screen.getByRole('combobox', { name }) as HTMLInputElement

/** The options the list offers, once it has opened by itself after the typing has paused */
async function offered() {
  await pause()
  const list = await screen.findByRole('listbox')
  return { list, names: within(list).getAllByRole('option').map((one) => one.textContent) }
}

const matterRoutes: Routes = { 'GET /v1/admin/matters': ({ query }: { query?: Record<string, unknown> }) => auditMatters(query) }

function MatterExample({ onChange = vi.fn(), start = null }: { onChange?: (matter: A['MatterBrief'] | null) => void; start?: A['MatterBrief'] | null }) {
  const [value, setValue] = useState<A['MatterBrief'] | null>(start)
  return (
    <MatterPicker
      value={value}
      onChange={(next) => {
        setValue(next)
        onChange(next)
      }}
    />
  )
}

describe('MatterPicker', () => {
  it("is a search over the Matters list: by number, name or client, each match as its number and name with its client's name", async () => {
    const onChange = vi.fn()
    const { api, view } = renderWith(<MatterExample onChange={onChange} />, matterRoutes)
    await userEvent.type(box('Matter'), ' kestrel ')
    const { list, names } = await offered()
    expect(names).toEqual(['1003.1 – Series A Investment Kestrel Software Ltd', '1003.2 – Licence Review Kestrel Software Ltd'])
    // One question, for the words once they settled, trimmed
    expect(api.callsTo('GET /v1/admin/matters').map((call) => call.query)).toEqual([{ q: 'kestrel', limit: MATTER_SEARCH_LIMIT }])
    // The list open, under axe
    await expectNoAxeViolations(view.container)
    await userEvent.click(within(list).getByRole('option', { name: '1003.2 – Licence Review Kestrel Software Ltd' }))
    // The matter, as other objects name one
    expect(onChange).toHaveBeenLastCalledWith({ id: 32, number: '1003.2', name: 'Licence Review' })
    expect(box('Matter').value).toBe('1003.2 – Licence Review')
  })

  it('finds a matter by its number, and one by its name, by the keys alone', async () => {
    const onChange = vi.fn()
    renderWith(<MatterExample onChange={onChange} />, matterRoutes)
    await userEvent.type(box('Matter'), '1002.')
    expect((await offered()).names).toEqual(['1002.1 – Commercial Lease Brightwell Bakeries Ltd', '1002.2 – Employment Tribunal Brightwell Bakeries Ltd'])
    // The list opened with no option focused, so the key moves to the first
    await userEvent.keyboard('{ArrowDown}{ArrowDown}{Enter}')
    expect(onChange).toHaveBeenLastCalledWith({ id: 22, number: '1002.2', name: 'Employment Tribunal' })

    await userEvent.clear(box('Matter'))
    await userEvent.type(box('Matter'), 'vessel')
    // The matter it holds is offered first while something is typed, as every picker's is
    expect((await offered()).names).toEqual(['1002.2 – Employment Tribunal', '1001.2 – Vessel Sale Saltmarsh Shipping Ltd'])
  })

  it('asks for nothing while nothing is typed', async () => {
    const { api } = renderWith(<MatterExample />, matterRoutes)
    await userEvent.type(box('Matter'), '   ')
    await pause()
    expect(api.callsTo('GET /v1/admin/matters')).toEqual([])
    expect(screen.queryByRole('listbox')).toBeNull()
  })

  it('shows the matter it holds, and picks nothing once emptied', async () => {
    const onChange = vi.fn()
    renderWith(<MatterExample start={{ id: 12, number: '1001.2', name: 'Vessel Sale' }} onChange={onChange} />, matterRoutes)
    expect(box('Matter').value).toBe('1001.2 – Vessel Sale')
    await userEvent.clear(box('Matter'))
    await userEvent.tab()
    expect(onChange).toHaveBeenLastCalledWith(null)
  })
})
