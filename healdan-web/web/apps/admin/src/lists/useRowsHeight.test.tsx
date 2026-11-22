// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { act, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { rowsHeight, useRowsHeight } from './useRowsHeight'

function List({ rows, shown }: { rows: number; shown: number }) {
  const { boxRef, height } = useRowsHeight(rows, shown)
  return (
    <div ref={boxRef} data-testid="box" style={{ height }}>
      <div role="grid">
        <div data-testid="content" />
      </div>
    </div>
  )
}

/** A ResizeObserver that says what it watches, and whose callback a test calls as a change of size would */
function stubResizeObserver() {
  const watched: Element[] = []
  const callbacks: (() => void)[] = []
  vi.stubGlobal(
    'ResizeObserver',
    class {
      constructor(callback: () => void) {
        callbacks.push(callback)
      }
      observe(element: Element) {
        watched.push(element)
      }
      disconnect() {}
    },
  )
  return { watched, resize: () => act(() => callbacks.forEach((callback) => callback())) }
}

/** The grid's height, and what it shows of it: less by what a sideways scroll bar takes */
function gridHeights(whole: number, shows: number) {
  vi.spyOn(HTMLElement.prototype, 'offsetHeight', 'get').mockReturnValue(whole)
  vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get').mockReturnValue(shows)
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
    stubResizeObserver()
    gridHeights(104, 104)
    render(<List rows={2} shown={12} />)
    expect(screen.getByTestId('box').style.height).toBe('104px')
  })

  it("adds the room a sideways scroll bar takes, so it doesn't cover the last row", () => {
    stubResizeObserver()
    gridHeights(104, 89)
    render(<List rows={2} shown={12} />)
    expect(screen.getByTestId('box').style.height).toBe('119px')
  })

  it('measures again when the box, the grid or what the grid holds changes size', () => {
    const observer = stubResizeObserver()
    gridHeights(104, 104)
    render(<List rows={2} shown={12} />)
    expect(observer.watched).toEqual([screen.getByTestId('box'), screen.getByRole('grid'), screen.getByTestId('content')])

    // A scroll bar comes, as when the columns no longer fit
    vi.restoreAllMocks()
    gridHeights(119, 104)
    observer.resize()
    expect(screen.getByTestId('box').style.height).toBe('119px')

    // And goes again
    vi.restoreAllMocks()
    gridHeights(104, 104)
    observer.resize()
    expect(screen.getByTestId('box').style.height).toBe('104px')
  })
})
