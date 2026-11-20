// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { components } from '@healdan/api'
import type { TableSort } from '@healdan/ui'
import { computerLabel } from '../actions/checkout'
import { typeLabel } from '../filters/FiltersPanel'
import type { ListRow } from './listColumns'

type S = components['schemas']

/** One part of a column's sort key: text (compared lower-cased, by UTF-16 code unit), a number, or nothing. */
type Part = string | number | null
/** A row's key for a column, or null when the row has no value in it. */
type Key = Part[] | null

const text = (value: string | null | undefined): Part => (value == null ? null : value.toLowerCase())
const time = (value: string | null | undefined): Part => {
  const at = value == null ? NaN : Date.parse(value)
  return Number.isNaN(at) ? null : at
}
// A date's key: no value at all when it can't be read, so the row sorts last both ways
const timeKey = (value: string | null | undefined): Key => {
  const at = time(value)
  return at === null ? null : [at]
}

// "998.10 – Name": the client's number and the matter's own, as the server formats the display; MatterRef has no number
// field of its own. A display that doesn't begin so has no value.
function matterKey(matter: S['MatterRef'] | null | undefined): Key {
  const found = matter && /^(\d+)\.(\d+)/.exec(matter.display)
  return found ? [Number(found[1]), Number(found[2])] : null
}

function extension(name: string): string {
  const dot = name.lastIndexOf('.')
  return dot < 0 ? '' : name.slice(dot + 1).toLowerCase()
}

function versionKey(version: string): Key {
  const found = /^(\d+)\.(\d+)/.exec(version)
  return found ? [Number(found[1]), Number(found[2])] : null
}

const documentOf = ({ item }: ListRow) => (item.type === 'document' ? item : null)

// The one source of the known columns: a column's key for a row. A row a column doesn't apply to has none.
const KEYS: Record<string, (row: ListRow) => Key> = {
  title: ({ item }) => [text(item.type === 'matter' ? item.display : item.name)],
  docNumber: (row) => { const d = documentOf(row); return d && [d.docNumber] },
  version: (row) => { const d = documentOf(row); return d && versionKey(d.fileVersion) },
  author: (row) => { const d = documentOf(row); return d?.author ? [text(d.author.displayName)] : null },
  edited: (row) => { const d = documentOf(row); return d && timeKey(d.lastModified) },
  matter: ({ item }) => (item.type === 'matter' ? null : matterKey(item.matter)),
  client: ({ item }) => {
    const name = item.type === 'matter' ? item.client.name : item.matter?.client.name
    return name == null ? null : [text(name)]
  },
  location: (row) => { const d = documentOf(row); return d && [text(d.folder.name), ...(matterKey(d.matter) ?? [null, null])] },
  filed: (row) => { const d = documentOf(row); return d && timeKey(d.dateFiled) },
  size: (row) => { const d = documentOf(row); return d && [d.size] },
  type: (row) => { const d = documentOf(row); return d && [text(typeLabel(d.kind)), extension(d.name)] },
  lastUsed: ({ item }) => (item.type === 'matter' ? null : timeKey(item.lastUsed)),
  computer: (row) => { const d = documentOf(row); return d?.hold ? [text(computerLabel(d.hold).computer)] : null },
  checkedOut: (row) => { const d = documentOf(row); return d?.hold ? timeKey(d.hold.since) : null },
}

function keyOf(column: string, row: ListRow): Key {
  return Object.hasOwn(KEYS, column) ? KEYS[column]!(row) : null
}

function comparePart(a: Part, b: Part): number {
  if (a === b) return 0
  if (a === null) return -1
  if (b === null) return 1
  return a < b ? -1 : a > b ? 1 : 0
}

function compareKeys(a: Part[], b: Part[]): number {
  for (let i = 0; i < Math.max(a.length, b.length); i++) {
    const order = comparePart(a[i] ?? null, b[i] ?? null)
    if (order !== 0) return order
  }
  return 0
}

/** Ascending, by the server's rules: text lower-cased by UTF-16 code unit (JavaScript's `<`, which agrees with PostgreSQL's `C` collation except for characters beyond the Basic Multilingual Plane against U+E000 to U+FFFF), numbers as numbers, dates by their time. A row with no value in the column compares equal here; `sortRows` puts it last. An unknown column compares every row equal. */
export function compareRows(column: string, a: ListRow, b: ListRow): number {
  if (!Object.hasOwn(KEYS, column)) return 0
  const x = keyOf(column, a)
  const y = keyOf(column, b)
  return x && y ? compareKeys(x, y) : 0
}

/** A new array: rows with a value sorted in the sort's direction, ties in the list's own order either way, then the rows with none, in the list's order. */
export function sortRows(rows: ListRow[], sort: TableSort): ListRow[] {
  const known = Object.hasOwn(KEYS, sort.column)
  const keyed = rows.map((row) => ({ row, key: known ? keyOf(sort.column, row) : [null] as Key }))
  const sign = sort.direction === 'descending' ? -1 : 1
  const have = keyed.filter((one) => one.key !== null).sort((a, b) => sign * compareKeys(a.key!, b.key!))
  const lack = keyed.filter((one) => one.key === null)
  return [...have, ...lack].map((one) => one.row)
}
