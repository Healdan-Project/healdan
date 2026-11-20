// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { expectNoAxeViolations } from '../test/axe'
import '../tokens.css'
import { GroupChip } from './GroupChip'
import chipCss from './GroupChip.module.css?raw'

describe('GroupChip', () => {
  it('shows a group\'s name as text, never as markup', async () => {
    const { container } = render(
      <p>
        <GroupChip name="Fee Earners" />
        <GroupChip name="<b>Partners</b>" />
      </p>,
    )
    expect(screen.getByText('Fee Earners')).toBeTruthy()
    expect(screen.getByText('<b>Partners</b>')).toBeTruthy()
    expect(container.querySelector('b')).toBeNull()
    await expectNoAxeViolations(container)
  })

  it('marks the administrators\' group apart, its name still saying which it is', async () => {
    const { container } = render(<GroupChip name="admin" admin />)
    const chip = screen.getByText('admin')
    expect(chip.getAttribute('data-admin')).toBe('true')
    await expectNoAxeViolations(container)
  })

  // jsdom draws no style, so the stylesheet is read: pairs tokens.test.ts checks reach AA in both themes
  it('draws its text in colours that reach AA on its face', () => {
    expect(chipCss).toMatch(/\.chip\s*\{(?=[^}]*background:\s*var\(--hd-pill\))(?=[^}]*color:\s*var\(--hd-text\))/)
    expect(chipCss).toMatch(/\.chip\[data-admin\]\s*\{(?=[^}]*background:\s*var\(--hd-danger-tint\))(?=[^}]*color:\s*var\(--hd-danger-text\))/)
  })
})
