// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { components } from '@healdan/api'
import { describe, expect, it } from 'vitest'
import { compareRows, sortRows } from './compareRows'
import { rowOf, type ListRow } from './listColumns'

type S = components['schemas']

function doc(n: number, over: Partial<S['DocumentView']> = {}): ListRow {
  const document: S['DocumentView'] = {
    type: 'document',
    docNumber: n,
    name: `doc${n}.pdf`,
    kind: 'pdf',
    size: 100,
    version: '1.0',
    fileVersion: '1.0',
    reference: `R${n}`,
    folder: { id: 1, name: 'Documents', kind: 'folder' },
    matter: { id: 1, display: '998.1 – Alpha', client: { id: 1, number: 998, name: 'Acme' } },
    author: { username: 'u', displayName: 'Ann' },
    dateFiled: '2026-01-01T00:00:00Z',
    lastModified: '2026-01-01T00:00:00Z',
    hold: null,
    canEdit: true,
    canOpen: true,
    ...over,
  } as S['DocumentView']
  return rowOf(document)
}
const matterRef = (display: string, number = 998, name = 'Acme'): S['MatterRef'] => ({ id: 1, display, client: { id: 1, number, name } })
const ids = (rows: ListRow[]) => rows.map((r) => (r.item.type === 'document' ? r.item.docNumber : -1))
const hold = (computer: string, since: string): S['HoldView'] =>
  ({ kind: 'checkout', since, heldOn: { name: computer, app: 'Word' } }) as unknown as S['HoldView']

// column, the values of the smaller row then the larger
const ascending: [string, Partial<S['DocumentView']>, Partial<S['DocumentView']>][] = [
  ['title', { name: 'apple.docx' }, { name: 'Banana.docx' }],
  ['docNumber', { docNumber: 9 }, { docNumber: 10 }],
  ['version', { fileVersion: '1.9' }, { fileVersion: '1.10' }],
  ['version', { fileVersion: '1.10' }, { fileVersion: '2.0' }],
  ['matter', { matter: matterRef('998.9 – Z') }, { matter: matterRef('998.10 – A') }],
  ['matter', { matter: matterRef('998.10 – A') }, { matter: matterRef('999.1 – A', 999) }],
  ['client', { matter: matterRef('1.1 – A', 1, 'acme') }, { matter: matterRef('2.1 – A', 2, 'Beta') }],
  ['location', { folder: { id: 1, name: 'alpha', kind: 'folder' } }, { folder: { id: 2, name: 'Beta', kind: 'folder' } }],
  ['location', { folder: { id: 1, name: 'A', kind: 'folder' }, matter: matterRef('998.9 – A') }, { folder: { id: 1, name: 'A', kind: 'folder' }, matter: matterRef('998.10 – A') }],
  ['author', { author: { username: 'u', displayName: 'ann' } }, { author: { username: 'u', displayName: 'Bob' } }],
  ['size', { size: 900 }, { size: 1000 }],
  ['edited', { lastModified: '2026-02-01T00:00:00Z' }, { lastModified: '2026-10-01T00:00:00Z' }],
  ['filed', { dateFiled: '2026-02-01T00:00:00Z' }, { dateFiled: '2026-10-01T00:00:00Z' }],
  ['lastUsed', { lastUsed: '2026-02-01T00:00:00Z' }, { lastUsed: '2026-10-01T00:00:00Z' }],
  ['type', { kind: 'excel', name: 'z.xlsx' }, { kind: 'word', name: 'a.docx' }],
  ['type', { kind: 'word', name: 'a.doc' }, { kind: 'word', name: 'a.DOCX' }],
  ['computer', { hold: hold('Ann’s Mac', '2026-01-01T00:00:00Z') }, { hold: hold('bob PC', '2026-01-01T00:00:00Z') }],
  ['checkedOut', { hold: hold('A', '2026-02-01T00:00:00Z') }, { hold: hold('A', '2026-10-01T00:00:00Z') }],
]

describe('compareRows, by the server\'s rules', () => {
  it.each(ascending)('%s: %j before %j', (column, small, large) => {
    const a = doc(1, small)
    const b = doc(2, large)
    expect(compareRows(column, a, b)).toBeLessThan(0)
    expect(compareRows(column, b, a)).toBeGreaterThan(0)
    expect(compareRows(column, a, a)).toBe(0)
  })

  it('compares upper case with lower case by UTF-16 code units after lower-casing, so Z follows a and _ precedes letters', () => {
    expect(compareRows('title', doc(1, { name: 'a.pdf' }), doc(2, { name: 'Z.pdf' }))).toBeLessThan(0)
    expect(compareRows('title', doc(1, { name: '_a.pdf' }), doc(2, { name: 'a.pdf' }))).toBeLessThan(0)
  })

  it('has no value for an unparseable date, so it sorts last both ways', () => {
    const rows = [doc(1, { lastModified: 'not a date' }), doc(2, { lastModified: '2026-02-01T00:00:00Z' }), doc(3, { lastModified: '2026-01-01T00:00:00Z' })]
    expect(ids(sortRows(rows, { column: 'edited', direction: 'ascending' }))).toEqual([3, 2, 1])
    expect(ids(sortRows(rows, { column: 'edited', direction: 'descending' }))).toEqual([2, 3, 1])
  })

  it('has a value for a doc # of 0', () => {
    const rows = [doc(1, { docNumber: 5 }), doc(2, { docNumber: 0 })]
    expect(ids(sortRows(rows, { column: 'docNumber', direction: 'ascending' }))).toEqual([0, 5])
  })

  it('has no Client for a row with no matter, and no Last Used for a row without one', () => {
    const rows = [doc(1, { matter: null }), doc(2, { matter: matterRef('1.1 – A', 1, 'Beta') }), doc(3, { matter: matterRef('1.1 – A', 1, 'Acme') })]
    expect(ids(sortRows(rows, { column: 'client', direction: 'ascending' }))).toEqual([3, 2, 1])
    expect(ids(sortRows(rows, { column: 'client', direction: 'descending' }))).toEqual([2, 3, 1])
    const used = [doc(1), doc(2, { lastUsed: '2026-01-01T00:00:00Z' })]
    expect(ids(sortRows(used, { column: 'lastUsed', direction: 'ascending' }))).toEqual([2, 1])
    expect(ids(sortRows(used, { column: 'lastUsed', direction: 'descending' }))).toEqual([2, 1])
  })

  it('compares every row equal for a column it does not know', () => {
    expect(compareRows('nope', doc(1), doc(2))).toBe(0)
  })
})

describe('sortRows', () => {
  it('sorts both ways, the last of a descending sort being the first of an ascending', () => {
    const rows = [doc(10), doc(9), doc(11)]
    expect(ids(sortRows(rows, { column: 'docNumber', direction: 'ascending' }))).toEqual([9, 10, 11])
    expect(ids(sortRows(rows, { column: 'docNumber', direction: 'descending' }))).toEqual([11, 10, 9])
    expect(ids(rows)).toEqual([10, 9, 11])
  })

  it('sorts Matter by client then matter number, a number without digits last both ways', () => {
    const rows = [
      doc(1, { matter: matterRef('999.1 – A', 999) }),
      doc(2, { matter: matterRef('No number') }),
      doc(3, { matter: matterRef('998.10 – A') }),
      doc(4, { matter: matterRef('998.9 – A') }),
      doc(5, { matter: null }),
    ]
    expect(ids(sortRows(rows, { column: 'matter', direction: 'ascending' }))).toEqual([4, 3, 1, 2, 5])
    expect(ids(sortRows(rows, { column: 'matter', direction: 'descending' }))).toEqual([1, 3, 4, 2, 5])
  })

  it('puts a part of a key with no value last both ways, as the server does for each part: Location\'s matter', () => {
    const documents = { id: 1, name: 'Documents', kind: 'folder' as const }
    const rows = [doc(1, { folder: documents, matter: matterRef('No number') }), doc(2, { folder: documents, matter: matterRef('1001.2 – B', 1001) }), doc(3, { folder: documents, matter: matterRef('1001.1 – A', 1001) })]
    expect(ids(sortRows(rows, { column: 'location', direction: 'ascending' }))).toEqual([3, 2, 1])
    expect(ids(sortRows(rows, { column: 'location', direction: 'descending' }))).toEqual([2, 3, 1])
  })

  it('puts missing values last both ways, in the list\'s order, and drops no row', () => {
    const rows = [doc(1, { author: null }), doc(2, { author: { username: 'u', displayName: 'Bob' } }), doc(3, { author: null }), doc(4, { author: { username: 'u', displayName: 'Ann' } })]
    expect(ids(sortRows(rows, { column: 'author', direction: 'ascending' }))).toEqual([4, 2, 1, 3])
    expect(ids(sortRows(rows, { column: 'author', direction: 'descending' }))).toEqual([2, 4, 1, 3])
  })

  it('has no value on a folder row for a document column, and sorts folders by name', () => {
    const folder = (id: number, name: string): ListRow => rowOf({ type: 'folder', id, name, kind: 'folder', matter: null, lastUsed: '2026-03-01T00:00:00Z' } as unknown as S['FolderView'])
    const rows = [folder(7, 'b'), doc(1), folder(8, 'A')]
    expect(sortRows(rows, { column: 'size', direction: 'ascending' }).map((r) => r.id)).toEqual([rows[1]!.id, rows[0]!.id, rows[2]!.id])
    expect(sortRows(rows, { column: 'title', direction: 'ascending' }).map((r) => r.id)).toEqual([rows[2]!.id, rows[0]!.id, rows[1]!.id])
  })

  it('keeps the list\'s own order for ties, in both directions', () => {
    const rows = [doc(1, { size: 5 }), doc(2, { size: 5 }), doc(3, { size: 1 }), doc(4, { size: 5 })]
    expect(ids(sortRows(rows, { column: 'size', direction: 'ascending' }))).toEqual([3, 1, 2, 4])
    expect(ids(sortRows(rows, { column: 'size', direction: 'descending' }))).toEqual([1, 2, 4, 3])
  })
})
