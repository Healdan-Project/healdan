// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { render } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { client } from '../test/fakeApi'
import type { components } from '@healdan/api'
import { formatDate, formatDateTime, type TableColumn } from '@healdan/ui'
import { DEFAULT_SHOWN, type DocumentListKind } from './columnLayouts'
import { columnsFor, hitColumns, MatterCell, offeredFor, rowOf, SERVER_SORTS, type ListRow } from './listColumns'

type S = components['schemas']

/** The column with that id, which each test expects to be there */
function column(columns: TableColumn<ListRow>[], id: string) {
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
    const columns = columnsFor('documents', DEFAULT_SHOWN.documents, true)
    expect(column(columns, 'title')).toMatchObject({ width: '2fr', minWidth: 220 })
    expect(column(columns, 'matter')).toMatchObject({ width: '1.5fr', minWidth: 100 })
    expect(column(columns, 'author')).toMatchObject({ width: '1fr' })
    for (const id of ['docNumber', 'version', 'edited']) expect(typeof column(columns, id).width).toBe('number')
  })

  it('fits beside the details or Filters at 1,280 pixels, where the list is 684 wide, without scrolling sideways', () => {
    // React Aria gives every column at least 75 pixels: a fixed width below that, or a share with no least width. This is
    // only the sum; a11y.spec.ts checks the list doesn't scroll sideways beside Filters in a real browser at 1,280
    const least = columnsFor('documents', DEFAULT_SHOWN.documents, true).reduce((sum, one) => sum + Math.max(75, typeof one.width === 'number' ? one.width : (one.minWidth ?? 0)), 0)
    expect(least).toBeLessThanOrEqual(684)
  })

  it("gives Checked Out to Me's and the Folders scope's Matter the same share, Checked Out to Me's at least its old 130", () => {
    const checkedOut = columnsFor('checkedOut', DEFAULT_SHOWN.checkedOut, false)
    expect(column(checkedOut, 'title')).toMatchObject({ width: '2fr', minWidth: 220 })
    // Its list is wider than the room beside the details anyway, so Matter needn't give way below its old fixed width
    expect(column(checkedOut, 'matter')).toMatchObject({ width: '1.5fr', minWidth: 130 })
    const folders = hitColumns('folders')
    expect(column(folders, 'title')).toMatchObject({ width: '2fr' })
    expect(column(folders, 'matter')).toMatchObject({ width: '1.5fr', minWidth: 100 })
  })
})

const matterRef: S['MatterRef'] = { id: 12, display: '1002.1 – Commercial Lease', client: client({ id: 2, number: 1002, name: 'Brightwell' }) }
const document = (over: Partial<S['DocumentView']> = {}): ListRow =>
  rowOf({
    type: 'document',
    docNumber: 10388,
    name: 'Lease.docx',
    kind: 'word',
    size: 1258291,
    version: '1.0',
    fileVersion: '1.0',
    reference: 'R1',
    folder: { id: 4, name: 'Documents', kind: 'folder' },
    matter: matterRef,
    author: { username: 'u', displayName: 'Ann' },
    dateFiled: '2026-01-05T10:00:00Z',
    lastModified: '2026-01-06T10:00:00Z',
    lastUsed: '2026-02-07T10:00:00Z',
    hold: null,
    canEdit: true,
    canOpen: true,
    ...over,
  } as S['DocumentView'])
const folderRow = (): ListRow =>
  rowOf({ type: 'folder', id: 4, name: 'Documents', kind: 'folder', matter: matterRef, lastUsed: '2026-02-07T10:00:00Z' } as unknown as S['FolderView'])
const cellOf = (kind: DocumentListKind, id: string, row: ListRow) => {
  const found = column(columnsFor(kind, offeredFor(kind), false), id)
  return render(<div>{found.cell(row)}</div>).container.firstElementChild!
}

describe('every document column (spec 4.7)', () => {
  it('offers every document column, Last Used only on Recent and Computer and Checked Out only on Checked Out to Me', () => {
    const common = ['title', 'docNumber', 'version', 'author', 'edited', 'matter', 'client', 'location', 'filed', 'size', 'type']
    expect(offeredFor('documents')).toEqual(common)
    expect(offeredFor('folder')).toEqual(common)
    expect(offeredFor('emails')).toEqual(common)
    expect(offeredFor('recentDocuments')).toEqual([...common, 'lastUsed'])
    expect(offeredFor('recentEmails')).toEqual([...common, 'lastUsed'])
    expect(offeredFor('checkedOut')).toEqual([...common, 'computer', 'checkedOut'])
    expect(offeredFor('beside')).toEqual(common)
  })

  it('returns the columns shown, in that order', () => {
    expect(columnsFor('documents', ['title', 'size', 'client'], false).map((one) => one.id)).toEqual(['title', 'size', 'client'])
  })

  it('keeps each list its own widths', () => {
    expect(column(columnsFor('folder', DEFAULT_SHOWN.folder, false), 'edited').width).toBe(132)
    expect(column(columnsFor('folder', DEFAULT_SHOWN.folder, false), 'title').width).toBe('3fr')
    expect(column(columnsFor('recentDocuments', DEFAULT_SHOWN.recentDocuments, false), 'edited').width).toBe(116)
    expect(column(columnsFor('checkedOut', DEFAULT_SHOWN.checkedOut, false), 'matter')).toMatchObject({ width: '1.5fr', minWidth: 130 })
  })

  it("lets Recent's default scroll sideways beside the details, as Checked Out to Me's does", () => {
    // The owner chose Last Used among Recent's defaults: its least widths pass 684, so beside the details the list scrolls
    // sideways (spec 4.3). Pinned so that a width change is a choice.
    const least = columnsFor('recentDocuments', DEFAULT_SHOWN.recentDocuments, false).reduce((sum, one) => sum + Math.max(75, typeof one.width === 'number' ? one.width : (one.minWidth ?? 0)), 0)
    expect(least).toBe(842)
  })

  it('shows nothing in a folder row for a document column, and its Matter and Last Used', () => {
    const row = folderRow()
    for (const id of ['docNumber', 'version', 'author', 'edited', 'location', 'filed', 'size', 'type']) {
      expect(cellOf('recentDocuments', id, row).textContent, id).toBe('')
    }
    expect(cellOf('recentDocuments', 'matter', row).textContent).toContain('1002.1 – Commercial Lease')
    expect(cellOf('recentDocuments', 'client', row).textContent).toBe('Brightwell')
    expect(cellOf('recentDocuments', 'lastUsed', row).textContent).toBe(formatDateTime('2026-02-07T10:00:00Z'))
  })

  it("shows Size, File Type, Client, Date Filed, Last Used, and Location's text and tooltips", () => {
    const row = document()
    expect(cellOf('recentDocuments', 'size', row).textContent).toBe('1.2 MB')
    expect(cellOf('recentDocuments', 'type', row).textContent).toBe('Word')
    expect(cellOf('recentDocuments', 'client', row).textContent).toBe('Brightwell')
    expect(cellOf('recentDocuments', 'filed', row).textContent).toBe(formatDate('2026-01-05T10:00:00Z'))
    expect(cellOf('recentDocuments', 'lastUsed', row).textContent).toBe(formatDateTime('2026-02-07T10:00:00Z'))
    const location = cellOf('recentDocuments', 'location', row)
    expect(location.textContent).toBe('Documents')
    expect(location.firstElementChild?.getAttribute('title')).toBe('Documents · 1002.1 – Commercial Lease')
    expect(cellOf('recentDocuments', 'location', document({ matter: null })).firstElementChild?.getAttribute('title')).toBe('Documents')
  })

  it('every document column sorts on a folder and a search', () => {
    // What a folder's and a search's sort take (spec 5.2), the columns PR 3 adds included
    expect([...SERVER_SORTS]).toEqual(['title', 'docNumber', 'version', 'author', 'edited', 'matter', 'client', 'location', 'filed', 'size', 'type', 'class', 'subclass', 'typist', 'from', 'to', 'sent'])
    for (const kind of ['folder', 'documents', 'all', 'emails'] as const) {
      const paged = columnsFor(kind, offeredFor(kind), true)
      expect(paged.map((one) => one.id)).toEqual(offeredFor(kind))
      for (const one of paged) expect(one.allowsSorting, `${kind} ${one.id}`).toBe(true)
    }
    for (const one of columnsFor('recentDocuments', offeredFor('recentDocuments'), false)) expect(one.allowsSorting, one.id).toBe(true)
    // Last Used, Computer and Checked Out are only the one-page lists', which the browser sorts: a paged list never sorts by them
    for (const id of ['lastUsed', 'computer', 'checkedOut']) expect(SERVER_SORTS.has(id), id).toBe(false)
  })

  it('the Folders scope sorts by Title and Matter, the Matters scope by Matter and Client, the Clients scope by Client', () => {
    const sorting = (columns: TableColumn<ListRow>[]) => columns.map((one) => [one.id, Boolean(one.allowsSorting)])
    expect(sorting(hitColumns('folders'))).toEqual([
      ['title', true],
      ['matter', true],
    ])
    // Their first column is the matter, the sort's name
    expect(sorting(hitColumns('matters'))).toEqual([
      ['matter', true],
      ['client', true],
    ])
    expect(sorting(hitColumns('clients'))).toEqual([
      ['matter', false],
      ['client', true],
    ])
  })

  it('date columns sort newest first at their first click', () => {
    const all = columnsFor('checkedOut', [...offeredFor('checkedOut'), 'lastUsed'], false)
    for (const one of all) expect(Boolean(one.newestFirst), one.id).toBe(['edited', 'filed', 'lastUsed', 'checkedOut'].includes(one.id))
  })

  it("with Client shown, Matter's cell doesn't repeat the client for a screen reader", () => {
    const row = document()
    const withClient = render(<div>{column(columnsFor('documents', ['title', 'matter', 'client'], false), 'matter').cell(row)}</div>).container.firstElementChild!
    expect(withClient.textContent).toBe('1002.1 – Commercial Lease')
    expect(withClient.firstElementChild?.getAttribute('title')).toBe('1002.1 – Commercial Lease · Brightwell')
    const without = render(<div>{column(columnsFor('documents', ['title', 'matter'], false), 'matter').cell(row)}</div>).container.firstElementChild!
    expect(without.textContent).toBe('1002.1 – Commercial Lease · Brightwell')
  })
})
