// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { render } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { client } from '../test/fakeApi'
import { checkedOutColumns, hitColumns, MatterCell } from './listColumns'

/** The column with that id, which each test expects to be there */
function column(columns: ReturnType<typeof hitColumns>, id: string) {
  const found = columns.find((one) => one.id === id)
  if (!found) throw new Error(`No ${id} column`)
  return found
}

describe('the Matter column', () => {
  it('shows the number and the name, with the whole of it and the client as its tooltip and for a screen reader', () => {
    const { container } = render(<MatterCell matter={{ id: 12, display: '1002.1 – Commercial Lease', client: client({ id: 2, number: 1002, name: 'Brightwell' }) }} />)
    const cell = container.firstElementChild!
    // Drawn: the number and the name
    expect(cell.firstChild?.textContent).toBe('1002.1 – Commercial Lease')
    // A name the column cuts short can still be read by pointing at it, as before
    expect(cell.getAttribute('title')).toBe('1002.1 – Commercial Lease · Brightwell')
    // A tooltip can't be reached by keyboard, so a screen reader reads the client with the cell, which isn't drawn
    expect(cell.textContent).toBe('1002.1 – Commercial Lease · Brightwell')
    expect(cell.lastElementChild?.className).toMatch(/hidden/)
  })

  it('a matter with no name shows its number', () => {
    const { container } = render(<MatterCell matter={{ id: 17, display: '1001.7', client: client() }} />)
    expect(container.firstElementChild?.firstChild?.textContent).toBe('1001.7')
  })
})

describe('the default widths (spec 4.1)', () => {
  it('gives Matter a share of the spare width beside Title and Author', () => {
    const columns = hitColumns('documents')
    expect(column(columns, 'title')).toMatchObject({ width: '2fr', minWidth: 220 })
    expect(column(columns, 'matter')).toMatchObject({ width: '1.5fr', minWidth: 100 })
    expect(column(columns, 'author')).toMatchObject({ width: '1fr' })
    for (const id of ['docNumber', 'version', 'edited']) expect(typeof column(columns, id).width).toBe('number')
  })

  it('fits beside the details or Filters at 1,280 pixels, where the list is 684 wide, without scrolling sideways', () => {
    // React Aria gives every column at least 75 pixels: a fixed width below that, or a share with no least width. This is
    // only the sum; a11y.spec.ts checks the list doesn't scroll sideways beside Filters in a real browser at 1,280
    const least = hitColumns('documents').reduce((sum, one) => sum + Math.max(75, typeof one.width === 'number' ? one.width : (one.minWidth ?? 0)), 0)
    expect(least).toBeLessThanOrEqual(684)
  })

  it("gives Checked Out to Me's and the Folders scope's Matter the same share, Checked Out to Me's at least its old 130", () => {
    const checkedOut = checkedOutColumns()
    expect(column(checkedOut, 'title')).toMatchObject({ width: '2fr', minWidth: 220 })
    // Its list is wider than the room beside the details anyway, so Matter needn't give way below its old fixed width
    expect(column(checkedOut, 'matter')).toMatchObject({ width: '1.5fr', minWidth: 130 })
    const folders = hitColumns('folders')
    expect(column(folders, 'title')).toMatchObject({ width: '2fr' })
    expect(column(folders, 'matter')).toMatchObject({ width: '1.5fr', minWidth: 100 })
  })
})
