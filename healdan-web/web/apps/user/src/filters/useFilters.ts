// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useNavigate, useSearch } from '@tanstack/react-router'
import { useMemo } from 'react'

/** The file kinds the API's `type` takes (not `folder`, which lists subfolders alone). */
export const fileTypes = ['word', 'excel', 'powerpoint', 'pdf', 'email', 'opendocument', 'picture', 'other'] as const
export const datePresets = ['today', '7d', '30d', 'year'] as const
export type DatePreset = (typeof datePresets)[number]

/** The filters that take several values, which the address repeats: ?type=pdf&type=word. */
export const multiFilters = ['type', 'author', 'class', 'client', 'matter'] as const
export type MultiFilter = (typeof multiFilters)[number]

/** What can be dropped from the chips: each of the multi-valued filters, and the edit date as one. */
export type FilterKind = MultiFilter | 'edited'

export interface Filters {
  type: string[]
  author: string[]
  class: string[]
  client: string[]
  matter: string[]
  edited?: DatePreset
  /** A range of days, as 2026-11-02; the API reads them, and Today, in the zone `filterParams` sends */
  from?: string
  to?: string
}

export const noFilters: Filters = { type: [], author: [], class: [], client: [], matter: [] }

const day = /^\d{4}-\d{2}-\d{2}$/

function isDay(value: unknown): value is string {
  if (typeof value !== 'string' || !day.test(value)) return false
  const made = new Date(`${value}T00:00:00Z`)
  return !Number.isNaN(made.getTime()) && made.toISOString().startsWith(value)
}

// Trimmed, as the API trims them, so a blank value (?author=%20), which it refuses, is left out like an empty one
function listOf(value: unknown): string[] {
  const all = Array.isArray(value) ? value : [value]
  return [...new Set(all.filter((one): one is string => typeof one === 'string').map((one) => one.trim()).filter((one) => one !== ''))]
}

// What the API would refuse with a 400 never goes to it: a pasted address with a client that isn't a number just
// leaves that filter out, rather than turning the list into an error.
const accepts: Record<MultiFilter, (value: string) => boolean> = {
  type: (value) => fileTypes.some((kind) => kind === value),
  author: () => true,
  class: () => true,
  client: (value) => /^\d{1,18}$/.test(value),
  matter: (value) => /^\d{1,18}\.\d{1,18}$/.test(value),
}

/** The filters an address holds; whatever is no filter, or no value the API takes, is left out. */
export function readFilters(search: Record<string, unknown>): Filters {
  const filters: Filters = { ...noFilters }
  for (const name of multiFilters) filters[name] = listOf(search[name]).filter(accepts[name])
  const from = isDay(search.from) ? search.from : undefined
  let to = isDay(search.to) ? search.to : undefined
  // The API refuses a range that ends before it starts
  if (from && to && from > to) to = undefined
  if (from || to) {
    filters.from = from
    filters.to = to
  } else {
    // A preset can't go with a range; the range wins in an address that holds both, so the API never sees both.
    const preset = datePresets.find((one) => one === search.edited)
    if (preset) filters.edited = preset
  }
  return filters
}

/** How many filters are in use: each kind of filter once, however many values it has, and the edit date once. */
export function activeCount(filters: Filters): number {
  const edited = filters.edited || filters.from || filters.to ? 1 : 0
  return multiFilters.filter((name) => filters[name].length > 0).length + edited
}

/** The browser's time zone, as an IANA id such as Europe/London; undefined in a browser that can't say. */
export function browserZone(): string | undefined {
  const zone = Intl.DateTimeFormat().resolvedOptions().timeZone
  return zone ? zone : undefined
}

/**
 * The filters as the API's query takes them, with the browser's time zone, which the server cuts Today and the days of
 * a range in: the dates the list shows are in it too (spec section 5, R90).
 */
export function filterParams(filters: Filters): Record<string, string | string[]> {
  const params: Record<string, string | string[]> = {}
  for (const name of multiFilters) if (filters[name].length > 0) params[name] = filters[name]
  if (filters.edited) params.edited = filters.edited
  if (filters.from) params.from = filters.from
  if (filters.to) params.to = filters.to
  const zone = browserZone()
  if (zone) params.zone = zone
  return params
}

/** The address's filters, for what the route validates; empty ones are left out so the address stays short. */
export function filterSearch(search: Record<string, unknown>): Partial<Filters> {
  const filters = readFilters(search)
  const out: Partial<Filters> = {}
  for (const name of multiFilters) if (filters[name].length > 0) out[name] = filters[name]
  if (filters.edited) out.edited = filters.edited
  if (filters.from) out.from = filters.from
  if (filters.to) out.to = filters.to
  return out
}

const cleared: Record<FilterKind, Partial<Record<keyof Filters, undefined>>> = {
  type: { type: undefined },
  author: { author: undefined },
  class: { class: undefined },
  client: { client: undefined },
  matter: { matter: undefined },
  edited: { edited: undefined, from: undefined, to: undefined },
}

export interface UseFilters {
  filters: Filters
  active: number
  /** Sets some of the filters (an empty list or undefined drops one) and keeps the rest of the address */
  change: (patch: Partial<Filters>) => void
  /** Drops one filter */
  remove: (kind: FilterKind) => void
  clear: () => void
}

/** The filters in the address (?type=pdf&author=pnair&edited=7d), which Back, bookmarks and pasted links keep (spec section 10.1). */
export function useFilters(): UseFilters {
  const search: Record<string, unknown> = useSearch({ strict: false })
  const navigate = useNavigate()
  const filters = useMemo(() => readFilters(search), [search])

  function change(patch: Partial<Filters>) {
    const next: Record<string, unknown> = {}
    for (const [name, value] of Object.entries(patch)) next[name] = Array.isArray(value) && value.length === 0 ? undefined : value
    // A preset and a range are alternatives
    if (patch.edited) Object.assign(next, { from: undefined, to: undefined })
    if (patch.from || patch.to) Object.assign(next, { edited: undefined })
    void navigate({ to: '.', replace: true, search: (prev) => ({ ...prev, ...next }) })
  }

  return {
    filters,
    active: activeCount(filters),
    change,
    remove: (kind) => change(cleared[kind]),
    clear: () => change(Object.assign({}, ...Object.values(cleared))),
  }
}
