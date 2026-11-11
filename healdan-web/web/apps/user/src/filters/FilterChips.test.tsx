// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { expectNoAxeViolations } from '../test/axe'
import { FilterChips } from './FilterChips'
import { noFilters } from './useFilters'

describe('FilterChips', () => {
  it('shows nothing with no filter in use', () => {
    const { container } = render(<FilterChips filters={noFilters} onRemove={() => {}} onClear={() => {}} onEmpty={() => {}} />)
    expect(container.textContent).toBe('')
  })

  it('has a chip for each filter in use, naming its values', () => {
    render(
      <FilterChips
        filters={{ ...noFilters, type: ['word', 'pdf'], author: ['pnair', 'gone'], class: ['Docs'], client: ['998'], matter: ['998.1'], edited: '7d' }}
        authors={new Map([['pnair', 'Priya Nair']])}
        onRemove={() => {}}
        onClear={() => {}} onEmpty={() => {}}
      />,
    )
    const chips = within(screen.getByRole('group', { name: 'Filters in Use' }))
    expect(chips.getByText('Edit Date: Last 7 Days')).toBeTruthy()
    expect(chips.getByText('File Type: Word, PDF')).toBeTruthy()
    // An Author nobody has named still shows, by username
    expect(chips.getByText('Author: Priya Nair, gone')).toBeTruthy()
    expect(chips.getByText('Class: Docs')).toBeTruthy()
    expect(chips.getByText('Client: 998')).toBeTruthy()
    expect(chips.getByText('Matter: 998.1')).toBeTruthy()
  })

  it('shows a range of days as the person reads them', () => {
    const { rerender } = render(<FilterChips filters={{ ...noFilters, from: '2026-11-01', to: '2026-11-09' }} onRemove={() => {}} onClear={() => {}} onEmpty={() => {}} />)
    expect(screen.getByText('Edit Date: Nov 1, 2026 – Nov 9, 2026')).toBeTruthy()
    rerender(<FilterChips filters={{ ...noFilters, from: '2026-11-01' }} onRemove={() => {}} onClear={() => {}} onEmpty={() => {}} />)
    expect(screen.getByText('Edit Date: From Nov 1, 2026')).toBeTruthy()
    rerender(<FilterChips filters={{ ...noFilters, to: '2026-11-09' }} onRemove={() => {}} onClear={() => {}} onEmpty={() => {}} />)
    expect(screen.getByText('Edit Date: Until Nov 9, 2026')).toBeTruthy()
  })

  it('drops one filter with its ×, or all with Clear All', async () => {
    const onRemove = vi.fn()
    const onClear = vi.fn()
    const onEmpty = vi.fn()
    const { container } = render(<FilterChips filters={{ ...noFilters, type: ['pdf'], from: '2026-11-01' }} onRemove={onRemove} onClear={onClear} onEmpty={onEmpty} />)
    await userEvent.click(screen.getByRole('button', { name: 'Remove File Type Filter' }))
    expect(onRemove).toHaveBeenCalledWith('type')
    await userEvent.click(screen.getByRole('button', { name: 'Remove Edit Date Filter' }))
    expect(onRemove).toHaveBeenLastCalledWith('edited')
    await userEvent.click(screen.getByRole('button', { name: 'Clear All' }))
    expect(onClear).toHaveBeenCalledOnce()
    // Clear All leaves no chip to take focus, so the funnel gets it
    expect(onEmpty).toHaveBeenCalled()
    await expectNoAxeViolations(container)
  })
})
