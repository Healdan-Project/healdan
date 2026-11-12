// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { expectNoAxeViolations } from '../test/axe'
import { SignedOut } from './SignedOut'

describe('SignedOut', () => {
  it('says so and offers Sign In, a link to the address the person is at', async () => {
    window.history.replaceState(null, '', '/app/folders/5?sel=10388')
    const { container } = render(<SignedOut />)
    // The page's one heading, so a screen reader has a place to start (axe's page-has-heading-one)
    expect(screen.getByRole('heading', { level: 1, name: "You've been signed out." })).toBeTruthy()
    const link = screen.getByRole('link', { name: 'Sign In' })
    expect(link.getAttribute('href')).toBe('/app/folders/5?sel=10388')
    await expectNoAxeViolations(container)
  })
})
