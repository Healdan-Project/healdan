// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { expectNoAxeViolations } from '../test/axe'
import { Button } from './Button'
import { Tabs } from './Tabs'
import css from './Tabs.module.css?raw'

function Harness({ initial = 'properties', onSelect = vi.fn() }: { initial?: string; onSelect?: (id: string) => void }) {
  const [selected, setSelected] = useState(initial)
  return (
    <Tabs
      label="Document Details"
      selected={selected}
      onSelect={(id) => {
        onSelect(id)
        setSelected(id)
      }}
      tabs={[
        { id: 'properties', label: 'Properties', content: <Button>Edit Properties</Button> },
        { id: 'versions', label: 'Versions (3)', content: <p>Version 2.1</p> },
      ]}
    />
  )
}

describe('Tabs', () => {
  it('shows the selected tab and its panel alone, named by the tab', () => {
    render(<Harness />)
    expect(screen.getByRole('tablist', { name: 'Document Details' })).toBeTruthy()
    expect(screen.getByRole('tab', { name: 'Properties' }).getAttribute('aria-selected')).toBe('true')
    expect(screen.getByRole('tab', { name: 'Versions (3)' }).getAttribute('aria-selected')).toBe('false')
    expect(screen.getByRole('tabpanel', { name: 'Properties' })).toBeTruthy()
    expect(screen.queryByText('Version 2.1')).toBeNull()
  })

  it('switches on a click, and tells the caller, who keeps the choice', async () => {
    const onSelect = vi.fn()
    render(<Harness onSelect={onSelect} />)
    await userEvent.click(screen.getByRole('tab', { name: 'Versions (3)' }))
    expect(onSelect).toHaveBeenCalledWith('versions')
    expect(screen.getByRole('tabpanel', { name: 'Versions (3)' }).textContent).toBe('Version 2.1')
  })

  it('moves between the tabs with the arrow keys, and Tab goes on into the panel', async () => {
    render(<Harness />)
    const properties = screen.getByRole('tab', { name: 'Properties' })
    properties.focus()
    fireEvent.keyDown(properties, { key: 'ArrowRight' })
    const versions = screen.getByRole('tab', { name: 'Versions (3)' })
    expect(document.activeElement).toBe(versions)
    expect(versions.getAttribute('aria-selected')).toBe('true')
    fireEvent.keyDown(versions, { key: 'ArrowLeft' })
    expect(document.activeElement).toBe(properties)
    await userEvent.tab()
    expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Edit Properties' }))
  })

  // A form being edited in a tab keeps what's typed while another tab shows, and doesn't take the focus again on return
  it('keeps a kept tab mounted, hidden and inert, while another shows', async () => {
    function Kept() {
      const [selected, setSelected] = useState('properties')
      return (
        <Tabs
          label="Document Details"
          selected={selected}
          onSelect={setSelected}
          tabs={[
            { id: 'properties', label: 'Properties', content: <input aria-label="Comments" />, keepMounted: true },
            { id: 'versions', label: 'Versions', content: <p>Version 2.1</p> },
          ]}
        />
      )
    }
    render(<Kept />)
    await userEvent.type(screen.getByRole('textbox', { name: 'Comments' }), 'Chase on Monday')
    const field = screen.getByRole('textbox', { name: 'Comments' })
    await userEvent.click(screen.getByRole('tab', { name: 'Versions' }))

    expect(screen.queryByRole('textbox', { name: 'Comments' })).toBeNull()
    expect(field.isConnected).toBe(true)
    expect(field.closest('[inert]')).toBeTruthy()
    expect(screen.getByRole('tabpanel', { name: 'Versions' }).textContent).toBe('Version 2.1')
    await userEvent.click(screen.getByRole('tab', { name: 'Properties' }))
    expect(screen.getByRole('textbox', { name: 'Comments' })).toBe(field)
    expect((field as HTMLInputElement).value).toBe('Chase on Monday')
  })

  it('starts on the tab it is given', () => {
    render(<Harness initial="versions" />)
    expect(screen.getByRole('tab', { name: 'Versions (3)' }).getAttribute('aria-selected')).toBe('true')
    expect(screen.getByText('Version 2.1')).toBeTruthy()
  })

  // jsdom draws no style, so the stylesheet is read: the selected segment is raised on a face of its own, and bolder
  it("draws the helper's segmented control, the selected segment raised and bolder", () => {
    expect(css).toMatch(/\.list\s*\{[^}]*background:\s*var\(--hd-pill\)/)
    const selected = /\.tab\[data-selected\]\s*\{([^}]*)\}/.exec(css)?.[1] ?? ''
    expect(selected).toMatch(/background:\s*var\(--hd-button-secondary\)/)
    expect(selected).toMatch(/font-weight:\s*var\(--hd-weight-bold\)/)
  })

  it('passes axe', async () => {
    const { container } = render(<Harness />)
    await expectNoAxeViolations(container)
  })
})
