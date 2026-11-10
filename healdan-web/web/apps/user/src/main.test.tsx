// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only
import { render, screen } from '@testing-library/react'
import { expect, test } from 'vitest'
import { App } from './main'

test('shows a main landmark that says Healdan', () => {
  render(<App />)
  expect(screen.getByRole('main').textContent).toContain('Healdan')
})
