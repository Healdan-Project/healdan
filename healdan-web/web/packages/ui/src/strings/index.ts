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
  const all: Vars = { ...labels, ...vars }
  return template.replace(/\{(\w+)\}/g, (whole, name: string) => (name in all ? String(all[name]) : whole))
}

export function t(key: StringKey, vars: Vars = {}): string {
  return fill(enUS[key], vars)
}

const rules = new Intl.PluralRules('en-US')

export function plural(key: PluralKey, count: number, vars: Vars = {}): string {
  const forms = enUSPlurals[key]
  const form = rules.select(count) === 'one' ? forms.one : forms.other
  return fill(form, { ...vars, n: count })
}

export type { PluralKey, StringKey }
