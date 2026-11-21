// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ApiError } from '@healdan/api'
import { ErrorPanel } from './ErrorPanel'
import { MessagesProvider } from './messages'

const own = new Map([['problems.only-here', 'A message only this app has.']])
const error = new ApiError(409, 'only-here', 'server text', {})

describe('ErrorPanel and the app catalogue', () => {
  it("shows the app's own message inside a MessagesProvider", () => {
    render(
      <MessagesProvider lookup={(key) => own.get(key)}>
        <ErrorPanel error={error} onRetry={() => {}} />
      </MessagesProvider>,
    )
    expect(screen.getByText('A message only this app has.')).toBeTruthy()
  })

  it('shows the shared catalogue with no provider', () => {
    render(<ErrorPanel error={error} onRetry={() => {}} />)
    expect(screen.getByText('Something went wrong.')).toBeTruthy()
  })
})
