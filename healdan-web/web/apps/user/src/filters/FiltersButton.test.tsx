// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { FiltersButton } from './FiltersButton'

describe('FiltersButton', () => {
  it('is named Filters, and unpressed, with none in use', () => {
    render(<FiltersButton open={false} active={0} onPress={() => {}} />)
    const button = screen.getByRole('button', { name: 'Filters' })
    expect(button.getAttribute('aria-pressed')).toBe('false')
    expect(button.textContent).toBe('Filters')
  })

  it('shows how many are in use, in its badge and its name', () => {
    render(<FiltersButton open active={2} onPress={() => {}} />)
    const button = screen.getByRole('button', { name: 'Filters 2 in Use' })
    expect(button.getAttribute('aria-pressed')).toBe('true')
    expect(button.textContent).toBe('Filters 2')
  })

  it('is pressed to open or close the panel', async () => {
    const onPress = vi.fn()
    render(<FiltersButton open={false} active={0} onPress={onPress} />)
    await userEvent.click(screen.getByRole('button', { name: 'Filters' }))
    expect(onPress).toHaveBeenCalledOnce()
  })
})
