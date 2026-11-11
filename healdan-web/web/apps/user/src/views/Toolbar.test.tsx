// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { countText, Toolbar } from './Toolbar'

describe('Toolbar', () => {
  it('says how many documents the list holds', () => {
    render(<Toolbar label="Correspondence" count={{ documents: 7 }} />)
    expect(screen.getByText('7 documents')).toBeTruthy()
  })

  it('counts in the person\'s words, with thousands grouped', () => {
    expect(countText({ documents: 1 })).toBe('1 document')
    expect(countText({ documents: 0 })).toBe('0 documents')
    expect(countText({ documents: 1234 })).toBe('1,234 documents')
  })

  it('says how many are shown of how many, once filters leave some out', () => {
    expect(countText({ documents: 3, of: 7 })).toBe('3 of 7 documents')
    expect(countText({ documents: 1, of: 1 })).toBe('1 of 1 document')
    expect(countText({ documents: 1200, of: 4500 })).toBe('1,200 of 4,500 documents')
  })

  it('says "more than" when the server stopped counting', () => {
    expect(countText({ atLeast: 10000 })).toBe('More than 10,000 documents')
  })

  it('reads its count out when it changes, and holds what the view puts in it', () => {
    render(
      <Toolbar label="Correspondence" count={{ documents: 2 }}>
        <button type="button">Upload</button>
      </Toolbar>,
    )
    expect(screen.getByRole('status').textContent).toBe('2 documents')
    expect(screen.getByRole('toolbar', { name: 'Correspondence' })).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Upload' })).toBeTruthy()
  })

  it('shows no count until there is one', () => {
    render(<Toolbar label="Correspondence" />)
    expect(screen.getByRole('status').textContent).toBe('')
  })

  it('puts what it is given for its end after the count, and is then a toolbar', () => {
    render(<Toolbar label="Folder Actions" count={{ documents: 7 }} end={<button>Filters</button>} />)
    const bar = screen.getByRole('toolbar', { name: 'Folder Actions' })
    expect(bar.lastElementChild?.textContent).toBe('Filters')
    expect(bar.children[0]?.textContent).toBe('7 documents')
  })
})
