// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { expectNoAxeViolations } from '../test/axe'
import { PickField, type PickFieldOption } from './PickField'

const jamie = { id: '3', label: 'Jamie Clarke' }
const pat = { id: '7', label: 'Pat Example' }

function Example({ start = jamie, onChange = vi.fn(), required, error }: { start?: PickFieldOption | null; onChange?: (value: PickFieldOption | null) => void; required?: boolean; error?: string }) {
  const [value, setValue] = useState<PickFieldOption | null>(start)
  const [text, setText] = useState('')
  const options = text === '' ? [] : [pat, jamie].filter((one) => one.label.toLowerCase().includes(text.toLowerCase()))
  return (
    <>
      <PickField
        label="Author"
        required={required}
        error={error}
        value={value}
        options={options}
        onTextChange={setText}
        onChange={(next) => {
          setValue(next)
          onChange(next)
        }}
      />
      <button type="button">After</button>
    </>
  )
}

const box = () => screen.getByRole('combobox', { name: /Author/ }) as HTMLInputElement

describe('PickField', () => {
  it('shows what is picked, under its label', () => {
    render(<Example />)
    expect(box().value).toBe('Jamie Clarke')
  })

  it('offers what is picked first, then the options found, and picks one by keyboard', async () => {
    const onChange = vi.fn()
    render(<Example onChange={onChange} />)
    await userEvent.clear(box())
    await userEvent.type(box(), 'pa')
    const list = await screen.findByRole('listbox')
    expect(within(list).getAllByRole('option').map((one) => one.textContent)).toEqual(['Jamie Clarke', 'Pat Example'])
    await userEvent.keyboard('{ArrowDown}{ArrowDown}{Enter}')
    expect(onChange).toHaveBeenLastCalledWith(pat)
    expect(box().value).toBe('Pat Example')
  })

  it('goes back to what was picked when left half typed, and picks nothing when left empty', async () => {
    const onChange = vi.fn()
    render(<Example onChange={onChange} />)
    await userEvent.clear(box())
    await userEvent.type(box(), 'p')
    await userEvent.tab()
    expect(box().value).toBe('Jamie Clarke')
    expect(onChange).not.toHaveBeenCalled()
    await userEvent.clear(box())
    await userEvent.tab()
    expect(box().value).toBe('')
    expect(onChange).toHaveBeenLastCalledWith(null)
  })

  it('offers nothing in an emptied box, and keeps what is picked when it is picked again from the list', async () => {
    const onChange = vi.fn()
    render(<Example onChange={onChange} />)
    await userEvent.clear(box())
    await userEvent.keyboard('{ArrowDown}')
    expect(screen.queryByRole('listbox')).toBeNull()

    await userEvent.type(box(), 'jam')
    await screen.findByRole('option', { name: 'Jamie Clarke' })
    await userEvent.keyboard('{ArrowDown}{Enter}')
    expect(box().value).toBe('Jamie Clarke')
    expect(onChange).not.toHaveBeenCalled()

    await userEvent.clear(box())
    await userEvent.type(box(), 'cla')
    await userEvent.click(await screen.findByRole('option', { name: 'Jamie Clarke' }))
    expect(box().value).toBe('Jamie Clarke')
    expect(onChange).not.toHaveBeenCalled()
  })

  it('marks a required field and says what is wrong with it, and passes axe', async () => {
    const { container } = render(<Example required error="This needs a value." start={null} />)
    expect(box().getAttribute('aria-required')).toBe('true')
    expect(box().getAttribute('aria-invalid')).toBe('true')
    expect(screen.getByText('This needs a value.')).toBeTruthy()
    await expectNoAxeViolations(container)
  })
})
