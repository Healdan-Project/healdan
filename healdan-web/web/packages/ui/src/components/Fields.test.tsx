// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { expectNoAxeViolations } from '../test/axe'
import { CheckField, SelectField, TextField } from './Fields'

describe('Fields', () => {
  it('marks a required text field and ties its error to it', async () => {
    const { container } = render(<TextField label="Class" value="" onChange={() => {}} required error="It needs a value." />)
    const input = screen.getByRole('textbox', { name: /Class/ })
    expect(input.getAttribute('aria-required')).toBe('true')
    expect(input.getAttribute('aria-invalid')).toBe('true')
    expect(input.getAttribute('aria-describedby')).toBeTruthy()
    expect(screen.getByText('It needs a value.')).toBeTruthy()
    await expectNoAxeViolations(container)
  })

  it('reports what is typed', async () => {
    const onChange = vi.fn()
    render(<TextField label="Comments" value="" onChange={onChange} />)
    await userEvent.type(screen.getByRole('textbox', { name: 'Comments' }), 'a')
    expect(onChange).toHaveBeenCalledWith('a')
  })

  it('offers only its options, and none unless it may have none', async () => {
    const options = [
      { value: 'Letters', label: 'Letters' },
      { value: 'Invoices', label: 'Invoices' },
    ]
    const onChange = vi.fn()
    const { container, rerender } = render(<SelectField label="Class" value="Letters" options={options} onChange={onChange} required />)
    expect(screen.getAllByRole('option').map((option) => option.textContent)).toEqual(['Letters', 'Invoices'])
    await userEvent.selectOptions(screen.getByRole('combobox', { name: /Class/ }), 'Invoices')
    expect(onChange).toHaveBeenCalledWith('Invoices')
    rerender(<SelectField label="Class" value="" options={options} onChange={onChange} empty="None" error="Pick one." />)
    expect(screen.getAllByRole('option').map((option) => option.textContent)).toEqual(['None', 'Letters', 'Invoices'])
    await expectNoAxeViolations(container)
  })

  it('keeps a value the list no longer has, instead of showing the first option in its place', () => {
    const options = [
      { value: 'Letters', label: 'Letters' },
      { value: 'Invoices', label: 'Invoices' },
    ]
    render(<SelectField label="Class" value="Old Class" options={options} onChange={() => {}} />)
    const select = screen.getByRole('combobox', { name: 'Class' }) as HTMLSelectElement
    expect(select.value).toBe('Old Class')
    expect(screen.getAllByRole('option').map((option) => option.textContent)).toEqual(['Old Class', 'Letters', 'Invoices'])
  })

  it('shows nothing chosen, not the first option, for a required list with no value yet', () => {
    const options = [{ value: 'Letters', label: 'Letters' }]
    render(<SelectField label="Class" value="" options={options} onChange={() => {}} required />)
    const select = screen.getByRole('combobox', { name: /Class/ }) as HTMLSelectElement
    expect(select.value).toBe('')
    expect(select.selectedOptions[0]?.disabled).toBe(true)
  })

  it('is a yes or no', async () => {
    const onChange = vi.fn()
    const { container } = render(<CheckField label="Signed" checked={false} onChange={onChange} />)
    await userEvent.click(screen.getByRole('checkbox', { name: 'Signed' }))
    expect(onChange).toHaveBeenCalledWith(true)
    await expectNoAxeViolations(container)
  })
})
