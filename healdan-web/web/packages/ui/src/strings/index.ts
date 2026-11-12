// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { enUS, enUSPlurals, type PluralKey, type StringKey } from './en-US'

export const strings = enUS

type Vars = Record<string, string | number>

let labels = { matter: 'Matter', matters: 'Matters' }

// The firm's words for a matter, from GET /server.
export function setLabels(next: { matter: string; matters: string }): void {
  labels = { matter: next.matter, matters: next.matters }
}

function fill(template: string, vars: Vars): string {
  // {matterLower} is the firm's word in the middle of a sentence
  const all: Vars = { ...labels, matterLower: labels.matter.toLowerCase(), ...vars }
  return template.replace(/\{(\w+)\}/g, (whole, name: string) => (name in all ? String(all[name]) : whole))
}

export function t(key: StringKey, vars: Vars = {}): string {
  return fill(enUS[key], vars)
}

const rules = new Intl.PluralRules('en-US')
const counts = new Intl.NumberFormat('en-US')

/**
 * The form for the count, as {n}. The count and any other number in vars are counts, so they're grouped as US English
 * groups them (1,234); t() leaves its numbers alone, as a doc # is never grouped.
 */
export function plural(key: PluralKey, count: number, vars: Vars = {}): string {
  const forms = enUSPlurals[key]
  const form = rules.select(count) === 'one' ? forms.one : forms.other
  const grouped = Object.fromEntries(Object.entries({ ...vars, n: count }).map(([name, value]) => [name, typeof value === 'number' ? counts.format(value) : value]))
  return fill(form, grouped)
}

export type { PluralKey, StringKey }
