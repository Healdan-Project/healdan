// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { expectNoAxeViolations } from '../test/axe'
import { NotYet } from './NotYet'

describe('NotYet', () => {
  it('says the view is coming', async () => {
    const { container } = render(<NotYet title="Search" />)
    expect(screen.getByRole('heading', { name: 'Search' })).toBeTruthy()
    expect(screen.getByText('Coming soon.')).toBeTruthy()
    await expectNoAxeViolations(container)
  })
})
