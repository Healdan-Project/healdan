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
  it('shows the number and the name, with the client as its tooltip', () => {
    const { container } = render(<MatterCell matter={{ id: 12, display: '1002.1 – Commercial Lease', client: client({ id: 2, number: 1002, name: 'Brightwell' }) }} />)
    expect(container.textContent).toBe('1002.1 – Commercial Lease')
    expect(container.querySelector('[title="Brightwell"]')).toBeTruthy()
  })

  it('a matter with no name shows its number', () => {
    const { container } = render(<MatterCell matter={{ id: 17, display: '1001.7', client: client() }} />)
    expect(container.textContent).toBe('1001.7')
  })
})

describe('the default widths (spec 4.1)', () => {
  it('gives Matter a share of the spare width beside Title and Author', () => {
    const columns = hitColumns('documents')
    expect(column(columns, 'title')).toMatchObject({ width: '2fr', minWidth: 220 })
    expect(column(columns, 'matter')).toMatchObject({ width: '1.5fr', minWidth: 160 })
    expect(column(columns, 'author')).toMatchObject({ width: '1fr' })
    for (const id of ['docNumber', 'version', 'edited']) expect(typeof column(columns, id).width).toBe('number')
  })

  it("gives Checked Out to Me's and the Folders scope's Matter the same share", () => {
    const checkedOut = checkedOutColumns()
    expect(column(checkedOut, 'title')).toMatchObject({ width: '2fr', minWidth: 220 })
    expect(column(checkedOut, 'matter')).toMatchObject({ width: '1.5fr', minWidth: 160 })
    const folders = hitColumns('folders')
    expect(column(folders, 'title')).toMatchObject({ width: '2fr' })
    expect(column(folders, 'matter')).toMatchObject({ width: '1.5fr', minWidth: 160 })
  })
})
