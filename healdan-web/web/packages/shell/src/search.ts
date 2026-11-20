// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

// How both apps' routers read and write an address's parameters (their parseSearch and stringifySearch). Addresses carry
// plain strings (?sel=10388, not ?sel="10388"), which TanStack Router's default JSON-aware search handling would quote.
// The names in `repeated` can repeat (?type=pdf&type=word), so those come as lists; anything else repeated keeps its
// last value. What no route reads is left alone here, and each route's validateSearch takes only what it knows.

export function parseSearch(search: string, repeated: readonly string[] = []): Record<string, string | string[]> {
  const params = new URLSearchParams(search)
  const parsed: Record<string, string | string[]> = Object.fromEntries(params)
  for (const name of repeated) if (params.has(name)) parsed[name] = params.getAll(name)
  return parsed
}

export function stringifySearch(search: Record<string, unknown>): string {
  const params = new URLSearchParams()
  for (const [name, value] of Object.entries(search)) {
    for (const one of Array.isArray(value) ? value : [value]) {
      if (one !== undefined && one !== null && one !== '') params.append(name, String(one))
    }
  }
  const text = params.toString()
  return text ? `?${text}` : ''
}
