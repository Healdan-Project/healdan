// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { rowsHeight, useRowsHeight } from './useRowsHeight'

function List({ rows, shown }: { rows: number; shown: number }) {
  const { boxRef, height } = useRowsHeight(rows, shown)
  return (
    <div ref={boxRef} data-testid="box" style={{ height }}>
      <div role="grid" />
    </div>
  )
}

afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('rowsHeight', () => {
  it('counts a heading, a row at least and the most rows shown', () => {
    expect(rowsHeight(0, 12)).toBe(2 * 34 + 2)
    expect(rowsHeight(2, 12)).toBe(3 * 34 + 2)
    expect(rowsHeight(40, 12)).toBe(13 * 34 + 2)
  })
})

describe('useRowsHeight', () => {
  it('is as high as its rows where no scroll bar takes room', () => {
    render(<List rows={2} shown={12} />)
    expect(screen.getByTestId('box').style.height).toBe('104px')
  })

  it("adds the room a sideways scroll bar takes, so it doesn't cover the last row", () => {
    vi.stubGlobal(
      'ResizeObserver',
      class {
        observe() {}
        disconnect() {}
      },
    )
    vi.spyOn(HTMLElement.prototype, 'offsetHeight', 'get').mockReturnValue(104)
    vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get').mockReturnValue(89)
    render(<List rows={2} shown={12} />)
    expect(screen.getByTestId('box').style.height).toBe('119px')
  })
})
