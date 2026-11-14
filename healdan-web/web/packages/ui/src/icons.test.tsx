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

  it.each(iconKinds)('has a rule and a token for the kind %s', (kind) => {
    expect(iconsCss).toMatch(new RegExp(`\\.${kind} \\{\\s*color: var\\(${token(kind)}\\);\\s*\\}`))
    expect(tokensCss).toContain(`${token(kind)}:`)
  })
})
