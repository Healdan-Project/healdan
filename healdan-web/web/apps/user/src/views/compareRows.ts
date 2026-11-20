// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { components } from '@healdan/api'
import type { TableSort } from '@healdan/ui'
import { computerLabel } from '../actions/checkout'
import { typeLabel } from '../filters/FiltersPanel'
import type { ListRow } from './listColumns'

type S = components['schemas']

/** One part of a column's sort key: text (compared lower-cased, by code point), a number, or nothing. */
type Part = string | number | null
/** A row's key for a column, or null when the row has no value in it. */
type Key = Part[] | null

const text = (value: string | null | undefined): Part => (value == null ? null : value.toLowerCase())
const time = (value: string | null | undefined): Part => {
  const at = value == null ? NaN : Date.parse(value)
  return Number.isNaN(at) ? null : at
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

function keyOf(column: string, { item }: ListRow): Key {
  const document = item.type === 'document' ? item : null
  switch (column) {
    case 'title':
      return [text(item.type === 'matter' ? item.display : item.name)]
    case 'docNumber':
      return document && [document.docNumber]
    case 'version':
      return document && versionKey(document.fileVersion)
    case 'author':
      return document?.author ? [text(document.author.displayName)] : null
    case 'edited':
      return document && [time(document.lastModified)]
    case 'matter':
      return item.type === 'matter' ? null : matterKey(item.matter)
    case 'client': {
      const name = item.type === 'matter' ? item.client.name : item.matter?.client.name
      return name == null ? null : [text(name)]
    }
    case 'location':
      return document && [text(document.folder.name), ...(matterKey(document.matter) ?? [null, null])]
    case 'filed':
      return document && [time(document.dateFiled)]
    case 'size':
      return document && [document.size]
    case 'type':
      return document && [text(typeLabel(document.kind)), extension(document.name)]
    case 'lastUsed':
      return item.type === 'matter' || !item.lastUsed ? null : [time(item.lastUsed)]
    case 'computer':
      return document?.hold ? [text(computerLabel(document.hold).computer)] : null
    case 'checkedOut':
      return document?.hold ? [time(document.hold.since)] : null
    default:
      return null
  }
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

const KNOWN = new Set(['title', 'docNumber', 'version', 'author', 'edited', 'matter', 'client', 'location', 'filed', 'size', 'type', 'lastUsed', 'computer', 'checkedOut'])

/** Ascending, by the server's rules: text lower-cased by code point, numbers as numbers, dates by their time. A row with no value in the column compares equal here; `sortRows` puts it last. An unknown column compares every row equal. */
export function compareRows(column: string, a: ListRow, b: ListRow): number {
  if (!KNOWN.has(column)) return 0
  const x = keyOf(column, a)
  const y = keyOf(column, b)
  return x && y ? compareKeys(x, y) : 0
}

/** A new array: rows with a value sorted in the sort's direction, ties in the list's own order either way, then the rows with none, in the list's order. */
export function sortRows(rows: ListRow[], sort: TableSort): ListRow[] {
  const known = KNOWN.has(sort.column)
  const keyed = rows.map((row) => ({ row, key: known ? keyOf(sort.column, row) : [null] as Key }))
  const sign = sort.direction === 'descending' ? -1 : 1
  const have = keyed.filter((one) => one.key !== null).sort((a, b) => sign * compareKeys(a.key!, b.key!))
  const lack = keyed.filter((one) => one.key === null)
  return [...have, ...lack].map((one) => one.row)
}
