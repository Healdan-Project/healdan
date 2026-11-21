// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it } from 'vitest'
import { createQueryClient, createSignedOut, ServicesProvider } from '@healdan/shell'
import { expectNoAxeViolations } from '../test/axe'
import { everyone, fakeApi, partners } from '../test/fakeApi'
import { GroupsField, type GroupsFieldItem } from './GroupsField'

/** The field over the fake API's groups, its list kept here as a form keeps it */
function Harness({ start, locked }: { start: GroupsFieldItem[]; locked?: number }) {
  const [items, setItems] = useState(start.map((item) => (item.id === locked ? { ...item, locked: 'Everyone but read-only accounts is in Everyone.' } : item)))
  return (
    <GroupsField
      label="Groups"
      items={items}
      empty="In no group."
      hint="Find a group by its name."
      onAdd={(group) => setItems([...items, { id: group.id, name: group.name }])}
      onRemove={(item) => setItems(items.filter((one) => one.id !== item.id))}
    />
  )
}

function renderField(start: GroupsFieldItem[], locked?: number) {
  const api = fakeApi()
  const view = render(
    <ServicesProvider api={api} signedOut={createSignedOut()}>
      <QueryClientProvider client={createQueryClient({ retry: false })}>
        <Harness start={start} locked={locked} />
      </QueryClientProvider>
    </ServicesProvider>,
  )
  return { api, view }
}

const listed = () => {
  const list = screen.queryByRole('list', { name: 'Groups' })
  return list ? within(list).getAllByRole('listitem').map((item) => item.firstChild?.textContent) : []
}
const offered = () => within(screen.getByRole('listbox')).getAllByRole('option').map((option) => option.textContent)

describe('A list of groups to add to', () => {
  it('lists the groups, each with Remove, and offers the others from a search', async () => {
    const { view } = renderField([{ id: partners.id, name: partners.name }])
    expect(listed()).toEqual(['Partners'])
    await waitFor(() => expect(offered()).toEqual(['Everyone Everyone in the firm', 'Trainees Trainee solicitors']))
    await userEvent.click(screen.getByRole('option', { name: /^Everyone/ }))
    expect(listed()).toEqual(['Partners', 'Everyone'])
    expect(offered()).toEqual(['Trainees Trainee solicitors'])
    await expectNoAxeViolations(view.container)
  })

  it('says so while there is none, and moves the focus on once one is removed: to the next Remove, or to Add a Group', async () => {
    renderField([
      { id: partners.id, name: partners.name },
      { id: everyone.id, name: everyone.name },
    ])
    await userEvent.click(screen.getByRole('button', { name: 'Remove Partners' }))
    expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Remove Everyone' }))
    await userEvent.click(screen.getByRole('button', { name: 'Remove Everyone' }))
    expect(listed()).toEqual([])
    expect(screen.getByText('In no group.')).toBeTruthy()
    expect(document.activeElement).toBe(screen.getByRole('textbox', { name: 'Add a Group' }))
  })

  it("offers no Remove for a group that can't be taken away, and says why", async () => {
    const { view } = renderField(
      [
        { id: everyone.id, name: everyone.name },
        { id: partners.id, name: partners.name },
      ],
      everyone.id,
    )
    expect(screen.queryByRole('button', { name: 'Remove Everyone' })).toBeNull()
    expect(screen.getByRole('button', { name: 'Remove Partners' })).toBeTruthy()
    const row = within(screen.getByRole('list', { name: 'Groups' })).getAllByRole('listitem')[0]!
    expect(row.textContent).toContain('Everyone but read-only accounts is in Everyone.')
    await expectNoAxeViolations(view.container)
  })
})
