// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { expectNoAxeViolations } from '../test/axe'
import { CheckGroup, type Choice } from './CheckGroup'
import { RadioChoices } from './RadioChoices'

const choices: Choice[] = [
  { value: 'word', label: 'Word', count: 2, name: 'Word, 2' },
  { value: 'pdf', label: 'PDF', count: 5, name: 'PDF, 5' },
]

function Group({ onChange = () => {} }: { onChange?: (value: string[]) => void }) {
  const [value, setValue] = useState<string[]>([])
  return (
    <CheckGroup
      label="File Type"
      choices={choices}
      value={value}
      onChange={(next) => {
        setValue(next)
        onChange(next)
      }}
    />
  )
}

describe('CheckGroup', () => {
  it('is a labelled group of tick boxes, each named with its count', () => {
    render(<Group />)
    expect(screen.getByRole('group', { name: 'File Type' })).toBeTruthy()
    expect(screen.getByRole('checkbox', { name: 'Word, 2' })).toBeTruthy()
    expect(screen.getByText('5')).toBeTruthy()
  })

  it('reports what is ticked, one more or fewer at a time', async () => {
    const onChange = vi.fn()
    render(<Group onChange={onChange} />)
    await userEvent.click(screen.getByRole('checkbox', { name: 'Word, 2' }))
    await userEvent.click(screen.getByRole('checkbox', { name: 'PDF, 5' }))
    expect(onChange).toHaveBeenLastCalledWith(['word', 'pdf'])
    await userEvent.click(screen.getByRole('checkbox', { name: 'Word, 2' }))
    expect(onChange).toHaveBeenLastCalledWith(['pdf'])
    expect((screen.getByRole('checkbox', { name: 'PDF, 5' }) as HTMLInputElement).checked).toBe(true)
  })

  it('passes axe', async () => {
    const { container } = render(<Group />)
    await expectNoAxeViolations(container)
  })
})

describe('RadioChoices', () => {
  it('is a labelled radio group that reports the choice', async () => {
    const onChange = vi.fn()
    const { container } = render(<RadioChoices label="Edit Date" choices={[{ value: 'any', label: 'Any Time' }, { value: 'today', label: 'Today' }]} value="any" onChange={onChange} />)
    expect(screen.getByRole('radiogroup', { name: 'Edit Date' })).toBeTruthy()
    expect((screen.getByRole('radio', { name: 'Any Time' }) as HTMLInputElement).checked).toBe(true)
    await userEvent.click(screen.getByRole('radio', { name: 'Today' }))
    expect(onChange).toHaveBeenCalledWith('today')
    await expectNoAxeViolations(container)
  })
})
