// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { expectNoAxeViolations } from '../test/axe'
import { Button } from './Button'
import { Tray, type TrayItem } from './Tray'

const items: TrayItem[] = [
  { id: 1, name: 'Lease.docx', state: 'filed', status: 'Filed as # 10500', actions: <Button>Edit</Button> },
  { id: 2, name: 'Plan.pdf', state: 'uploading', status: 'Uploading…' },
  { id: 3, name: 'Big.zip', state: 'refused', status: 'That file is too large.' },
]

describe('Tray', () => {
  it('is a named region that lists each item with its state', () => {
    render(<Tray label="Filing" summary="1 of 3 Filed" items={items} onDismiss={() => {}} dismissLabel={(name) => `Dismiss ${name}`} collapseLabel="Collapse" expandLabel="Expand" />)
    const region = screen.getByRole('region', { name: 'Filing' })
    expect(within(region).getByRole('status').textContent).toBe('1 of 3 Filed')
    const rows = within(region).getAllByRole('listitem')
    expect(rows.map((row) => row.getAttribute('data-state'))).toEqual(['filed', 'uploading', 'refused'])
    expect(within(rows[0]!).getByText('Filed as # 10500')).toBeTruthy()
    expect(within(rows[2]!).getByText('That file is too large.')).toBeTruthy()
  })

  it('is marked as a top layer, which a modal\'s hiding of the page leaves out, so its status is still announced', () => {
    render(<Tray label="Filing" items={items} onDismiss={() => {}} dismissLabel={(name) => `Dismiss ${name}`} collapseLabel="Collapse" expandLabel="Expand" />)
    expect(screen.getByRole('region', { name: 'Filing' }).getAttribute('data-react-aria-top-layer')).toBe('true')
  })

  it('dismisses only a finished item, from the keyboard', async () => {
    const dismiss = vi.fn()
    render(<Tray label="Filing" items={items} onDismiss={dismiss} dismissLabel={(name) => `Dismiss ${name}`} collapseLabel="Collapse" expandLabel="Expand" />)
    expect(screen.queryByRole('button', { name: 'Dismiss Plan.pdf' })).toBeNull()
    const button = screen.getByRole('button', { name: 'Dismiss Big.zip' })
    button.focus()
    await userEvent.keyboard('{Enter}')
    expect(dismiss).toHaveBeenCalledWith(3)
  })

  it('shows nothing when there are no items', () => {
    render(<Tray label="Filing" items={[]} onDismiss={() => {}} dismissLabel={(name) => name} collapseLabel="Collapse" expandLabel="Expand" />)
    expect(screen.queryByRole('region')).toBeNull()
  })

  it('passes axe', async () => {
    const { container } = render(<Tray label="Filing" summary="1 of 3 Filed" items={items} onDismiss={() => {}} dismissLabel={(name) => `Dismiss ${name}`} collapseLabel="Collapse" expandLabel="Expand" />)
    await expectNoAxeViolations(container)
  })

  it('folds to its summary, and clears the finished items but not the running ones', async () => {
    const clear = vi.fn()
    render(<Tray label="Filing" summary="1 of 3 Filed" items={items} onDismiss={() => {}} dismissLabel={(n) => n} onClear={clear} clearLabel="Clear Finished" collapseLabel="Collapse" expandLabel="Expand" />)
    await userEvent.click(screen.getByRole('button', { name: 'Clear Finished' }))
    expect(clear).toHaveBeenCalled()
    await userEvent.click(screen.getByRole('button', { name: 'Collapse' }))
    expect(screen.queryByText('Plan.pdf')).toBeNull()
    expect(screen.getByRole('status').textContent).toBe('1 of 3 Filed')
  })
})

