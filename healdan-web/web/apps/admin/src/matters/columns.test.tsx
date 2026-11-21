// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { describe, expect, it } from 'vitest'
import { matterColumns, matterRowOf } from './columns'
import { matterSorts } from './useMatterList'
import { matterRow } from '../test/fakeApi'

describe("A list of matters' columns", () => {
  it('are every column the server sorts by, each sorting, in the spec\'s order', () => {
    const columns = matterColumns()
    expect(columns.map((column) => column.id)).toEqual([...matterSorts])
    expect(columns.every((column) => column.allowsSorting)).toBe(true)
    // The number and the name name a row
    expect(columns.filter((column) => column.isRowHeader).map((column) => column.id)).toEqual(['number', 'name'])
  })

  it('leave out what a list doesn\'t need, as a client\'s page has no Client column', () => {
    expect(matterColumns(['client']).map((column) => column.name)).toEqual(['Matter', 'Name', 'Store', 'Owner', 'Template', 'Documents', 'Size', 'Last Activity'])
  })

  it('key a row by the matter\'s id, and give it its number and name as text', () => {
    expect(matterRowOf(matterRow())).toMatchObject({ id: '11', textValue: '1001.1 – Charter Dispute – MV Northern Star' })
  })
})
