// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import type { components } from '@healdan/api'
import { expectNoAxeViolations } from '../test/axe'
import { FiltersPanel, type FiltersPanelProps } from './FiltersPanel'
import { noFilters, type Filters } from './useFilters'

type S = components['schemas']

const facets: S['Facets'] = {
  type: [
    { value: 'pdf', count: 3 },
    { value: 'word', count: 4 },
    { value: 'opendocument', count: 1 },
  ],
  author: [
    { value: 'pnair', displayName: 'Priya Nair', count: 5 },
    { value: 'jclarke', displayName: 'Jamie Clarke', count: 2 },
  ],
  class: [{ value: 'Correspondence', count: 6 }],
  client: [{ value: '1001', count: 4 }],
  matter: [{ value: '1001.1', count: 4 }],
}

function Panel({ initial = noFilters, onChange = () => {}, ...rest }: Partial<FiltersPanelProps> & { initial?: Filters }) {
  const [filters, setFilters] = useState(initial)
  return (
    <FiltersPanel
      filters={filters}
      facets={facets}
      context="folder"
      onClose={() => {}}
      onChange={(patch) => {
        setFilters((prev) => ({ ...prev, ...patch }))
        onChange(patch)
      }}
      {...rest}
    />
  )
}

describe('FiltersPanel', () => {
  it('offers Edit Date, File Type, Author and Class, each option with its count', () => {
    render(<Panel />)
    const dates = within(screen.getByRole('radiogroup', { name: 'Edit Date' }))
    expect(dates.getAllByRole('radio').map((r) => r.closest('label')?.textContent)).toEqual(['Any Time', 'Today', 'Last 7 Days', 'Last 30 Days', 'This Year', 'Between…'])
    expect((dates.getByRole('radio', { name: 'Any Time' }) as HTMLInputElement).checked).toBe(true)
    expect(within(screen.getByRole('group', { name: 'File Type' })).getAllByRole('checkbox').map((c) => c.getAttribute('aria-label'))).toEqual(['PDF, 3', 'Word, 4', 'OpenDocument, 1'])
    expect(screen.getByRole('checkbox', { name: 'Priya Nair, 5' })).toBeTruthy()
    expect(screen.getByRole('checkbox', { name: 'Correspondence, 6' })).toBeTruthy()
  })

  it('offers Client and Matter only in search results', () => {
    const { rerender } = render(<Panel />)
    expect(screen.queryByRole('group', { name: 'Client' })).toBeNull()
    expect(screen.queryByRole('group', { name: 'Matter' })).toBeNull()
    rerender(<Panel context="search" />)
    expect(screen.getByRole('checkbox', { name: '1001, 4' })).toBeTruthy()
    expect(screen.getByRole('checkbox', { name: '1001.1, 4' })).toBeTruthy()
    expect(screen.getByRole('group', { name: 'Matter' })).toBeTruthy()
  })

  it("says the counts are the most relevant matches' when the server says they are partial", () => {
    const { rerender } = render(<Panel />)
    expect(screen.queryByText('Counts from the most relevant matches')).toBeNull()
    rerender(<Panel facets={{ ...facets, partial: true }} />)
    expect(screen.getByText('Counts from the most relevant matches')).toBeTruthy()
  })

  it('reports ticked options as the filter\'s whole list, and an unticked one as one fewer', async () => {
    const onChange = vi.fn()
    render(<Panel onChange={onChange} />)
    await userEvent.click(screen.getByRole('checkbox', { name: 'PDF, 3' }))
    await userEvent.click(screen.getByRole('checkbox', { name: 'Word, 4' }))
    expect(onChange).toHaveBeenLastCalledWith({ type: ['pdf', 'word'] })
    await userEvent.click(screen.getByRole('checkbox', { name: 'PDF, 3' }))
    expect(onChange).toHaveBeenLastCalledWith({ type: ['word'] })
  })

  it('sets a preset, which takes any range away, and Any Time clears it', async () => {
    const onChange = vi.fn()
    render(<Panel onChange={onChange} initial={{ ...noFilters, from: '2026-11-01' }} />)
    await userEvent.click(screen.getByRole('radio', { name: 'Last 30 Days' }))
    expect(onChange).toHaveBeenLastCalledWith({ edited: '30d', from: undefined, to: undefined })
    await userEvent.click(screen.getByRole('radio', { name: 'Any Time' }))
    expect(onChange).toHaveBeenLastCalledWith({ edited: undefined, from: undefined, to: undefined })
  })

  it('shows From and To for Between…, and reports each day as the API takes it', async () => {
    const onChange = vi.fn()
    render(<Panel onChange={onChange} />)
    expect(screen.queryByRole('group', { name: 'From' })).toBeNull()
    await userEvent.click(screen.getByRole('radio', { name: 'Between…' }))
    expect(onChange).toHaveBeenLastCalledWith({ edited: undefined })
    expect(screen.getByRole('group', { name: 'To' })).toBeTruthy()
    await userEvent.click(within(screen.getByRole('group', { name: 'From' })).getByRole('spinbutton', { name: /month/i }))
    await userEvent.keyboard('11012026')
    expect(onChange).toHaveBeenLastCalledWith({ from: '2026-11-01' })
  })

  it('shows a range from the address as Between…, with its days', () => {
    render(<Panel initial={{ ...noFilters, from: '2026-11-01', to: '2026-11-09' }} />)
    expect((screen.getByRole('radio', { name: 'Between…' }) as HTMLInputElement).checked).toBe(true)
    expect(within(screen.getByRole('group', { name: 'To' })).getByRole('spinbutton', { name: /day/i }).textContent).toBe('9')
  })

  it('keeps a ticked value that no option offers, so it can still be unticked', async () => {
    const onChange = vi.fn()
    render(<Panel onChange={onChange} initial={{ ...noFilters, author: ['gone'] }} />)
    const gone = screen.getByRole('checkbox', { name: 'gone, 0' }) as HTMLInputElement
    expect(gone.checked).toBe(true)
    await userEvent.click(gone)
    expect(onChange).toHaveBeenLastCalledWith({ author: [] })
  })

  it('keeps a ticked value that no option offers without a made-up 0 when the counts cover only the most relevant matches', async () => {
    const onChange = vi.fn()
    render(<Panel onChange={onChange} facets={{ ...facets, partial: true }} initial={{ ...noFilters, author: ['gone'] }} />)
    expect(screen.queryByRole('checkbox', { name: 'gone, 0' })).toBeNull()
    const gone = screen.getByRole('checkbox', { name: 'gone' }) as HTMLInputElement
    expect(gone.checked).toBe(true)
    await userEvent.click(gone)
    expect(onChange).toHaveBeenLastCalledWith({ author: [] })
  })

  it('shows what is ticked, without counts, while the counts are asked for', () => {
    render(<Panel facets={undefined} initial={{ ...noFilters, type: ['pdf'] }} />)
    expect((screen.getByRole('checkbox', { name: 'PDF' }) as HTMLInputElement).checked).toBe(true)
    expect(screen.getByText('Loading…')).toBeTruthy()
  })

  it('says so when the counts could not be had', () => {
    render(<Panel facets={undefined} unavailable />)
    expect(screen.getByText("The counts aren't available right now.")).toBeTruthy()
    expect(screen.queryByText('Loading…')).toBeNull()
  })

  it('marks the options busy while their counts are being asked for again', () => {
    const { rerender, container } = render(<Panel />)
    expect(container.querySelector('[aria-busy="true"]')).toBeNull()
    rerender(<Panel busy />)
    expect(container.querySelector('[aria-busy="true"]')).toBeTruthy()
  })

  it('closes from its button', async () => {
    const onClose = vi.fn()
    render(<Panel onClose={onClose} />)
    await userEvent.click(screen.getByRole('button', { name: 'Close Filters' }))
    expect(onClose).toHaveBeenCalledOnce()
  })

  it('passes axe', async () => {
    const { container } = render(<Panel context="search" initial={{ ...noFilters, type: ['pdf'], from: '2026-11-01' }} />)
    await expectNoAxeViolations(container)
  })
})
