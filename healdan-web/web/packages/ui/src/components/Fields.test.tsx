// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { render, screen } from '@testing-library/react'
import { createRef } from 'react'
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

  it('ties a description to its field, under its label, as a screen reader reads it after the name', async () => {
    const { container } = render(<TextField label="Read Cache (GB)" value="20" onChange={() => {}} description="From 1 to 10,000." />)
    const input = screen.getByRole('textbox', { name: 'Read Cache (GB)' })
    const described = (input.getAttribute('aria-describedby') ?? '').split(' ').map((id) => document.getElementById(id)?.textContent)
    expect(described).toContain('From 1 to 10,000.')
    await expectNoAxeViolations(container)
  })

  it('hides what is typed in a secret, which the browser neither fills nor keeps', async () => {
    const onChange = vi.fn()
    const { container } = render(<TextField label="Secret" type="password" autoComplete="new-password" value="" onChange={onChange} />)
    const input = screen.getByLabelText('Secret')
    expect(input.getAttribute('type')).toBe('password')
    expect(input.getAttribute('autocomplete')).toBe('new-password')
    await userEvent.type(input, 'x')
    expect(onChange).toHaveBeenCalledWith('x')
    await expectNoAxeViolations(container)
  })

  it('takes the browser off filling a field that holds a code, such as a key', () => {
    render(<TextField label="Recovery Key" autoComplete="off" value="" onChange={() => {}} />)
    const input = screen.getByRole('textbox', { name: 'Recovery Key' })
    expect(input.getAttribute('autocomplete')).toBe('off')
    expect(input.getAttribute('spellcheck')).toBe('false')
  })

  it('reports what is typed', async () => {
    const onChange = vi.fn()
    render(<TextField label="Comments" value="" onChange={onChange} />)
    await userEvent.type(screen.getByRole('textbox', { name: 'Comments' }), 'a')
    expect(onChange).toHaveBeenCalledWith('a')
  })

  it('takes several lines when multiline, under its label, and reports the line breaks typed', async () => {
    const onChange = vi.fn()
    const { container } = render(<TextField label="Description" value="" onChange={onChange} multiline rows={4} required />)
    const box = screen.getByRole('textbox', { name: /Description/ })
    expect(box.tagName).toBe('TEXTAREA')
    expect(box.getAttribute('rows')).toBe('4')
    expect(box.getAttribute('aria-required')).toBe('true')
    expect(container.querySelector('input')).toBeNull()
    await userEvent.type(box, '{Enter}')
    expect(onChange).toHaveBeenLastCalledWith('\n')
    await expectNoAxeViolations(container)
  })

  it('takes three lines when multiline with no rows given', () => {
    render(<TextField label="Notes" value={'One\nTwo'} onChange={() => {}} multiline />)
    const box = screen.getByRole('textbox', { name: 'Notes' }) as HTMLTextAreaElement
    expect(box.getAttribute('rows')).toBe('3')
    expect(box.value).toBe('One\nTwo')
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

  it('hands its list to a ref, so a form can move the focus to it', () => {
    const ref = createRef<HTMLSelectElement>()
    render(<SelectField label="Class" value="" options={[{ value: 'Letters', label: 'Letters' }]} onChange={() => {}} ref={ref} />)
    expect(ref.current).toBe(screen.getByRole('combobox', { name: 'Class' }))
  })

  it('shows nothing chosen, not the first option, for a required list with no value yet', () => {
    const options = [{ value: 'Letters', label: 'Letters' }]
    render(<SelectField label="Class" value="" options={options} onChange={() => {}} required />)
    const select = screen.getByRole('combobox', { name: /Class/ }) as HTMLSelectElement
    expect(select.value).toBe('')
    expect(select.selectedOptions[0]?.disabled).toBe(true)
  })

  it('is named by its label without drawing it, when the label is hidden, as in a row whose column heading says what it is', async () => {
    const options = [
      { value: 'read-only', label: 'Read Only' },
      { value: 'read-write', label: 'Read/Write' },
    ]
    const { container } = render(<SelectField label="Access for Everyone" labelHidden value="read-only" options={options} onChange={() => {}} />)
    expect(screen.getByRole('combobox', { name: 'Access for Everyone' })).toBeTruthy()
    const label = container.querySelector('label')!
    expect(label.textContent).toBe('Access for Everyone')
    expect(getComputedStyle(label).position).toBe('absolute')
    expect(getComputedStyle(label).width).toBe('1px')
    await expectNoAxeViolations(container)
  })

  it("ties a yes or no's description to its box, as a screen reader reads it after the name", async () => {
    const { container } = render(<CheckField label="Encrypt" checked onChange={() => {}} description="It can't change once the backend is added." />)
    const box = screen.getByRole('checkbox', { name: 'Encrypt' })
    const described = (box.getAttribute('aria-describedby') ?? '').split(' ').map((id) => document.getElementById(id)?.textContent)
    expect(described).toEqual(["It can't change once the backend is added."])
    await expectNoAxeViolations(container)
  })

  it('ties both a description and an error to a yes or no', () => {
    render(<CheckField label="Encrypt" checked={false} onChange={() => {}} description="Recommended." error="It has to be answered." />)
    const box = screen.getByRole('checkbox', { name: 'Encrypt' })
    const described = (box.getAttribute('aria-describedby') ?? '').split(' ').map((id) => document.getElementById(id)?.textContent)
    expect(described).toEqual(['Recommended.', 'It has to be answered.'])
  })

  it('is a yes or no', async () => {
    const onChange = vi.fn()
    const { container } = render(<CheckField label="Signed" checked={false} onChange={onChange} />)
    await userEvent.click(screen.getByRole('checkbox', { name: 'Signed' }))
    expect(onChange).toHaveBeenCalledWith(true)
    await expectNoAxeViolations(container)
  })
})
