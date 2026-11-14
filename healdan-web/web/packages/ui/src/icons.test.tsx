// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { render } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import './tokens.css'
import { Icon, iconKinds } from './icons'
import iconsCss from './icons.module.css?raw'
import tokensCss from './tokens.css?raw'

// The token a kind draws in: `wordProcessing` is `--hd-kind-word-processing`
const token = (kind: string) => `--hd-kind-${kind.replace(/[A-Z]/g, (letter) => `-${letter.toLowerCase()}`)}`

// The components' stylesheets, to find their accent fills
const sheets = import.meta.glob<string>('./components/*.module.css', { query: '?raw', import: 'default', eager: true })

describe('Icon', () => {
  it('draws a kind in its colour and says which kind it is, so a surface can draw it in its own', () => {
    const { container } = render(<Icon name="filePdf" kind="pdf" className="extra" />)
    const svg = container.querySelector('svg')!
    expect(svg.getAttribute('data-kind')).toBe('pdf')
    expect(svg.getAttribute('class')).toMatch(/\bpdf\b/)
    expect(svg.getAttribute('class')).toMatch(/\bextra\b/)
    expect(svg.getAttribute('aria-hidden')).toBe('true')
    expect(getComputedStyle(svg).color).toBe('rgb(196, 22, 28)')
  })

  it('leaves an icon of no kind in the colour of what it sits in', () => {
    const { container } = render(
      <p style={{ color: 'rgb(1, 2, 3)' }}>
        <Icon name="document" />
      </p>,
    )
    const svg = container.querySelector('svg')!
    expect(svg.hasAttribute('data-kind')).toBe(false)
    expect(getComputedStyle(svg).color).toBe('rgb(1, 2, 3)')
  })

  it('draws a kind in the text\'s colour on a fill that asks for it', () => {
    const { container } = render(
      <p style={{ color: 'rgb(1, 2, 3)', ['--hd-icon-on-fill' as string]: 'currentColor' }}>
        <Icon name="folder" kind="folder" />
      </p>,
    )
    expect(getComputedStyle(container.querySelector('svg')!).color).toBe('rgb(1, 2, 3)')
  })

  it('keeps its size beside text that is cut short, whoever lays it out', () => {
    const { container } = render(<Icon name="briefcase" kind="matter" />)
    expect(getComputedStyle(container.querySelector('svg')!).flexShrink).toBe('0')
  })

  it.each(iconKinds)('has a rule and a token for the kind %s', (kind) => {
    expect(iconsCss).toMatch(new RegExp(`\\.${kind} \\{\\s*color: var\\(--hd-icon-on-fill, var\\(${token(kind)}\\)\\);\\s*\\}`))
    expect(tokensCss).toContain(`${token(kind)}:`)
  })

  // A kind's colour fails on the accent fill (the folders' is the accent itself), so every fill with text on it draws a
  // kind's icon in the text's colour, as Table, Menu and ComboBox do
  it('is drawn in the text\'s colour on every accent fill', () => {
    const fills = Object.entries(sheets).flatMap(([file, css]) =>
      [...css.replace(/\/\*[\s\S]*?\*\//g, '').matchAll(/([^{}]+)\{([^{}]*)\}/g)]
        .filter(([, , body]) => /background:\s*var\(--hd-accent-fill\)/.test(body!) && /color:\s*var\(--hd-accent-on-fill\)/.test(body!))
        .map(([, selector, body]) => [`${file} ${selector!.trim()}`, /--hd-icon-on-fill:\s*currentColor;/.test(body!)]),
    )
    expect(fills.length).toBeGreaterThanOrEqual(6)
    for (const [fill, asks] of fills) expect([fill, asks]).toEqual([fill, true])
  })
})
