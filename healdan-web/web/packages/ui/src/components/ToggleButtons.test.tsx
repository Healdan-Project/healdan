// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { expectNoAxeViolations } from '../test/axe'
import { ToggleButtons } from './ToggleButtons'

const choices = [
  { value: 'mine', label: 'Mine' },
  { value: 'everyone', label: "Everyone's" },
  { value: 'removed', label: 'Removed' },
]

describe('ToggleButtons', () => {
  it('is a named group of radio buttons, the value pressed', async () => {
    const { container } = render(<ToggleButtons label="Show" choices={choices} value="mine" onChange={() => {}} />)
    const group = screen.getByRole('radiogroup', { name: 'Show' })
    expect(within(group).getAllByRole('radio').map((one) => [one.textContent, one.getAttribute('aria-checked')])).toEqual([
      ['Mine', 'true'],
      ["Everyone's", 'false'],
      ['Removed', 'false'],
    ])
    await expectNoAxeViolations(container)
  })

  it('says which was pressed, and nothing when the pressed one is pressed again', () => {
    const onChange = vi.fn()
    render(<ToggleButtons label="Show" choices={choices} value="mine" onChange={onChange} />)
    fireEvent.click(screen.getByRole('radio', { name: 'Removed' }))
    expect(onChange).toHaveBeenCalledWith('removed')
    fireEvent.click(screen.getByRole('radio', { name: 'Mine' }))
    expect(onChange).toHaveBeenCalledTimes(1)
  })

  it('moves between the buttons with the arrow keys, and Space presses one', async () => {
    const onChange = vi.fn()
    render(<ToggleButtons label="Show" choices={choices} value="mine" onChange={onChange} />)
    await userEvent.tab()
    expect(document.activeElement?.textContent).toBe('Mine')
    await userEvent.keyboard('{ArrowRight}')
    expect(document.activeElement?.textContent).toBe("Everyone's")
    await userEvent.keyboard(' ')
    expect(onChange).toHaveBeenCalledWith('everyone')
  })
})
