// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { ApiError } from '@healdan/api'
import { expectNoAxeViolations } from '../test/axe'
import { ErrorPanel } from './ErrorPanel'

describe('ErrorPanel', () => {
  it('shows a short message and Try Again, which asks again', () => {
    const retry = vi.fn()
    render(<ErrorPanel error={new ApiError(500, 'server-error', 'Stack: at com.x', {})} onRetry={retry} />)
    expect(screen.getByText('Something went wrong.')).toBeTruthy()
    expect(document.body.textContent).not.toContain('Stack')
    fireEvent.click(screen.getByRole('button', { name: 'Try Again' }))
    expect(retry).toHaveBeenCalledTimes(1)
  })

  it('leaves the words to the banner for a storage outage, and keeps Try Again', () => {
    render(<ErrorPanel error={new ApiError(503, 'storage-unreachable', '', {})} onRetry={() => {}} />)
    expect(screen.queryByText(/unavailable/)).toBeNull()
    expect(screen.getByRole('button', { name: 'Try Again' })).toBeTruthy()
  })

  it('passes axe', async () => {
    const { container } = render(<ErrorPanel error={new TypeError('x')} onRetry={() => {}} />)
    await expectNoAxeViolations(container)
  })
})
