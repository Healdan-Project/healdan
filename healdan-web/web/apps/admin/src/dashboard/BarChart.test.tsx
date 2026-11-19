// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { render, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { expectNoAxeViolations } from '../test/axe'
import { BarChart } from './BarChart'
import chartCss from './BarChart.module.css?raw'

const days = [
  { date: '2026-11-17', count: 4 },
  { date: '2026-11-18', count: 0 },
  { date: '2026-11-19', count: 8 },
]

const heightOf = (bar: Element) => Number(bar.getAttribute('height'))

describe('BarChart', () => {
  it('is an image named for what it shows, with a bar for each day, the tallest as tall as the chart', async () => {
    const { container } = render(<BarChart label="Documents Filed, Last 30 Days" days={days} />)
    const chart = screen.getByRole('img', { name: 'Documents Filed, Last 30 Days' })
    const bars = [...chart.querySelectorAll('[data-bar]')]
    expect(bars).toHaveLength(3)
    expect(heightOf(bars[2]!)).toBe(Number(chart.getAttribute('viewBox')!.split(' ')[3]))
    expect(heightOf(bars[0]!)).toBe(heightOf(bars[2]!) / 2)
    expect(heightOf(bars[1]!)).toBe(0)
    await expectNoAxeViolations(container)
  })

  it('has its numbers in a table that is hidden from sight and read by a screen reader', () => {
    render(<BarChart label="Documents Filed, Last 30 Days" days={days} />)
    const table = screen.getByRole('table', { name: 'Documents Filed, Last 30 Days' })
    expect(table.closest('[data-visually-hidden]')).toBeTruthy()
    expect(within(table).getAllByRole('columnheader').map((cell) => cell.textContent)).toEqual(['Day', 'Documents'])
    // Each day heads its row
    const rows = within(table).getAllByRole('row').slice(1).map((row) => [within(row).getByRole('rowheader').textContent, within(row).getByRole('cell').textContent])
    expect(rows).toEqual([['Nov 17, 2026', '4'], ['Nov 18, 2026', '0'], ['Nov 19, 2026', '8']])
  })

  it('names the first day and today under the bars, for sight only', () => {
    const { container } = render(<BarChart label="Documents Filed, Last 30 Days" days={days} />)
    const axis = container.querySelector('[data-axis]')!
    expect(axis.getAttribute('aria-hidden')).toBe('true')
    expect(axis.textContent).toBe('Nov 17Today')
  })

  it('draws days with nothing filed as no bars, without dividing by nothing', () => {
    render(<BarChart label="Quiet" days={[{ date: '2026-11-19', count: 0 }]} />)
    const bars = [...screen.getByRole('img', { name: 'Quiet' }).querySelectorAll('[data-bar]')]
    expect(bars.map(heightOf)).toEqual([0])
  })

  // jsdom draws no style, so the stylesheet is read: the bars are the accent's text colour, which tokens.test.ts checks
  // at 3:1 on every plain surface in both themes (as a progress bar's fill), and the baseline and axis are tokens too
  it('draws in tokens that pass in both themes', () => {
    const css = chartCss.replace(/\/\*[\s\S]*?\*\//g, '')
    expect(css).toMatch(/\.bar\s*\{[^}]*fill:\s*var\(--hd-accent-text\)/)
    expect(css).toMatch(/\.axis\s*\{[^}]*color:\s*var\(--hd-text-secondary\)/)
    expect(css).toMatch(/\.baseline\s*\{[^}]*stroke:\s*var\(--hd-line\)/)
    expect(css).not.toMatch(/#[0-9a-f]{3,8}\b/i)
  })
})
