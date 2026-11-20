// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { expectNoAxeViolations } from '../test/axe'
import { DatePicker } from './DatePicker'

describe('DatePicker', () => {
  it('shows a given day in its parts', () => {
    render(<DatePicker label="From" value="2026-11-02" onChange={() => {}} />)
    expect(screen.getByRole('group', { name: 'From' })).toBeTruthy()
    expect(screen.getByRole('spinbutton', { name: /month/i }).textContent).toBe('11')
    expect(screen.getByRole('spinbutton', { name: /day/i }).textContent).toBe('2')
    expect(screen.getByRole('spinbutton', { name: /year/i }).textContent).toBe('2026')
  })

  it('reports a typed day as 2026-11-02', async () => {
    const onChange = vi.fn()
    function Harness() {
      const [value, setValue] = useState<string>()
      return (
        <DatePicker
          label="From"
          value={value}
          onChange={(next) => {
            onChange(next)
            setValue(next)
          }}
        />
      )
    }
    render(<Harness />)
    await userEvent.click(screen.getByRole('spinbutton', { name: /month/i }))
    await userEvent.keyboard('11022026')
    // Only whole days, never the year on its way to 2026
    expect(onChange.mock.calls).toEqual([['2026-11-02']])
  })

  it('takes nothing while disabled, as while the form it is in is being saved', async () => {
    const onChange = vi.fn()
    render(<DatePicker label="From" value="2026-11-02" isDisabled onChange={onChange} />)
    const month = screen.getByRole('spinbutton', { name: /month/i })
    expect(month.getAttribute('aria-disabled')).toBe('true')
    await userEvent.click(month)
    await userEvent.keyboard('{ArrowUp}')
    expect(onChange).not.toHaveBeenCalled()
    expect(screen.getByRole('button').hasAttribute('disabled')).toBe(true)
  })

  it('ignores a value that is not a day', () => {
    render(<DatePicker label="From" value="2026-13-45" onChange={() => {}} />)
    expect(screen.getByRole('spinbutton', { name: /month/i }).textContent).toMatch(/mm/i)
  })

  it('opens a calendar from its button, and picks a day', async () => {
    const onChange = vi.fn()
    render(<DatePicker label="From" value="2026-11-02" onChange={onChange} />)
    await userEvent.click(screen.getByRole('button', { name: /calendar/i }))
    await userEvent.click(await screen.findByRole('button', { name: /November 10, 2026/ }))
    expect(onChange).toHaveBeenLastCalledWith('2026-11-10')
  })

  it('passes axe', async () => {
    const { container } = render(<DatePicker label="From" value="2026-11-02" onChange={() => {}} />)
    await expectNoAxeViolations(container)
  })
})
