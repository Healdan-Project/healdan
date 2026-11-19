// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { expectNoAxeViolations } from '../test/axe'
import '../tokens.css'
import { ProgressBar } from './ProgressBar'
import progressCss from './ProgressBar.module.css?raw'

describe('ProgressBar', () => {
  it('is a progress bar named by its label, with how far it has got as a count, and its detail as its description', async () => {
    const { container } = render(<ProgressBar label="Reindexing 1001.1 Charter Dispute" value={212} total={412} detail="About 6 minutes left" />)
    const bar = screen.getByRole('progressbar', { name: 'Reindexing 1001.1 Charter Dispute' })
    expect(bar.getAttribute('aria-valuenow')).toBe('212')
    expect(bar.getAttribute('aria-valuemin')).toBe('0')
    expect(bar.getAttribute('aria-valuemax')).toBe('412')
    // A screen reader hears the count, not a percentage
    expect(bar.getAttribute('aria-valuetext')).toBe('212 of 412')
    expect(screen.getByText('212 of 412')).toBeTruthy()
    const description = document.getElementById(bar.getAttribute('aria-describedby') ?? '')
    expect(description?.textContent).toBe('About 6 minutes left')
    await expectNoAxeViolations(container)
  })

  it('fills its share of the track, and groups the counts as US English does', () => {
    const { container } = render(<ProgressBar label="Reindexing" value={1119} total={1284} />)
    const fill = container.querySelector<HTMLElement>('[data-fill]')!
    expect(parseFloat(fill.style.width)).toBeCloseTo(87.15, 1)
    expect(screen.getByRole('progressbar').getAttribute('aria-valuetext')).toBe('1,119 of 1,284')
    expect(screen.getByRole('progressbar').hasAttribute('aria-describedby')).toBe(false)
  })

  it('draws an empty track for nothing to do, and never more than a full one', () => {
    const { container, rerender } = render(<ProgressBar label="Reindexing" value={0} total={0} />)
    const fill = () => container.querySelector<HTMLElement>('[data-fill]')!
    expect(fill().style.width).toBe('0%')
    rerender(<ProgressBar label="Reindexing" value={13} total={12} />)
    expect(fill().style.width).toBe('100%')
  })

  // jsdom draws no style, so the stylesheet is read: the fill and the track are colours tokens.test.ts checks show
  // against each other and the surfaces, in both themes
  it('draws its fill in the colour that shows on its track', () => {
    expect(progressCss).toMatch(/\.track\s*\{[^}]*background:\s*var\(--hd-line\)/)
    expect(progressCss).toMatch(/\.fill\s*\{[^}]*background:\s*var\(--hd-accent-text\)/)
  })
})
