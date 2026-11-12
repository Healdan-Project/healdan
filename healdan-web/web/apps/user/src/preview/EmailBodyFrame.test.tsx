// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { expectNoAxeViolations } from '../test/axe'
import { EmailBodyFrame } from './EmailBodyFrame'

describe('EmailBodyFrame', () => {
  it('shows the address in a frame that can run no script and has no origin of its own, whatever it is given', () => {
    render(<EmailBodyFrame src="/api/v1/documents/10388/message/body" />)
    const frame = screen.getByTitle('Message Body')
    expect(frame.tagName).toBe('IFRAME')
    expect(frame.getAttribute('src')).toBe('/api/v1/documents/10388/message/body')
    // Exactly these two: a link opens in a window of its own, which is not sandboxed by the frame
    expect(frame.getAttribute('sandbox')).toBe('allow-popups allow-popups-to-escape-sandbox')
    expect(frame.getAttribute('sandbox')).not.toContain('allow-scripts')
    expect(frame.getAttribute('sandbox')).not.toContain('allow-same-origin')
    expect(frame.getAttribute('referrerpolicy')).toBe('no-referrer')
  })

  it('passes axe', async () => {
    const { container } = render(<EmailBodyFrame src="/api/v1/documents/10388/message/body" />)
    await expectNoAxeViolations(container)
  })
})
