// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import axe from 'axe-core'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { expectNoAxeViolations } from '../test/axe'
import { ComboBox, type ComboBoxSection } from './ComboBox'

const sections: ComboBoxSection[] = [
  { id: 'matters', title: 'Matters', options: [{ id: 'm1', textValue: '1001.1 Charter Dispute', label: '1001.1 – Charter Dispute', meta: 'Saltmarsh' }] },
  { id: 'documents', title: 'Documents', options: [{ id: 'd1', textValue: 'Charter.docx', label: 'Charter.docx', meta: '# 10392' }] },
  { id: 'footer', title: 'Search', showTitle: false, options: [{ id: 'search', textValue: 'Search Documents for "ch"', label: 'Search Documents for "ch"' }] },
]

function Example({ onSelect = vi.fn(), onSubmit = vi.fn(), shown = sections }: { onSelect?: (id: string) => void; onSubmit?: (text: string) => void; shown?: ComboBoxSection[] }) {
  const [value, setValue] = useState('')
  return <ComboBox aria-label="Search" value={value} onValueChange={setValue} sections={shown} onSelect={onSelect} onSubmit={onSubmit} />
}

function type(text: string) {
  const box = screen.getByRole('combobox', { name: 'Search' })
  box.focus()
  fireEvent.change(box, { target: { value: text } })
  return box
}

describe('ComboBox', () => {
  it('offers its sections as it is typed in, each named', async () => {
    render(<Example />)
    type('ch')
    const list = await screen.findByRole('listbox')
    expect(within(list).getAllByRole('group').map((g) => g.getAttribute('aria-label') ?? g.textContent)).toEqual(expect.arrayContaining([expect.stringContaining('Matters'), expect.stringContaining('Documents')]))
    expect(within(list).getAllByRole('option').map((o) => o.textContent)).toEqual([
      '1001.1 – Charter DisputeSaltmarsh',
      'Charter.docx# 10392',
      'Search Documents for "ch"',
    ])
  })

  it('picks with the arrow keys and Enter, calling onSelect and not onSubmit', async () => {
    const onSelect = vi.fn()
    const onSubmit = vi.fn()
    render(<Example onSelect={onSelect} onSubmit={onSubmit} />)
    const box = type('ch')
    await screen.findByRole('listbox')
    fireEvent.keyDown(box, { key: 'ArrowDown' })
    fireEvent.keyDown(box, { key: 'ArrowDown' })
    fireEvent.keyDown(box, { key: 'Enter' })
    expect(onSelect).toHaveBeenCalledWith('d1')
    expect(onSubmit).not.toHaveBeenCalled()
  })

  it('submits the text on Enter when nothing is picked, with or without options showing', async () => {
    const onSelect = vi.fn()
    const onSubmit = vi.fn()
    render(<Example onSelect={onSelect} onSubmit={onSubmit} />)
    const box = type('ch')
    await screen.findByRole('listbox')
    fireEvent.keyDown(box, { key: 'Enter' })
    expect(onSubmit).toHaveBeenCalledWith('ch')
    expect(onSelect).not.toHaveBeenCalled()
    expect(screen.queryByRole('listbox')).toBeNull()
  })

  it('closes after an option is picked, whatever the caller does with the text', async () => {
    render(<Example />)
    const box = type('ch')
    await screen.findByRole('listbox')
    fireEvent.keyDown(box, { key: 'ArrowDown' })
    fireEvent.keyDown(box, { key: 'Enter' })
    await waitFor(() => expect(screen.queryByRole('listbox')).toBeNull())
  })

  it('can pick the same option twice in a row', async () => {
    const onSelect = vi.fn()
    render(<Example onSelect={onSelect} />)
    for (let i = 0; i < 2; i++) {
      const box = type(`ch${i}`)
      await screen.findByRole('listbox')
      fireEvent.keyDown(box, { key: 'ArrowDown' })
      fireEvent.keyDown(box, { key: 'Enter' })
    }
    expect(onSelect.mock.calls).toEqual([['m1'], ['m1']])
  })

  it('shows no list when it has no options', () => {
    render(<Example shown={[]} />)
    type('zz')
    expect(screen.queryByRole('listbox')).toBeNull()
  })

  it('closes on Escape, keeping what was typed', async () => {
    render(<Example />)
    const box = type('ch')
    await screen.findByRole('listbox')
    fireEvent.keyDown(box, { key: 'Escape' })
    expect(screen.queryByRole('listbox')).toBeNull()
    expect((box as HTMLInputElement).value).toBe('ch')
  })

  it('passes axe, closed and open', async () => {
    const { container } = render(
      <main>
        <Example />
      </main>,
    )
    await expectNoAxeViolations(container)
    type('ch')
    await screen.findByRole('listbox')
    // The list is drawn in React Aria's overlay layer at the end of <body>, outside the page's landmarks by design, so the
    // landmark rule can't hold for it.
    const result = await axe.run(document.body, { rules: { 'color-contrast': { enabled: false }, region: { enabled: false } } })
    expect(result.violations.map((v) => `${v.id}: ${v.nodes.map((n) => n.html).join(' | ')}`)).toEqual([])
  })
})
