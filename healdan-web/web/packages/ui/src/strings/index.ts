// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { enUS, enUSPlurals, type PluralKey, type StringKey } from './en-US'

export const strings = enUS

export type Vars = Record<string, string | number>

/** A plural's forms, which Intl.PluralRules('en-US') picks between */
export interface PluralForms {
  one: string
  other: string
}

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

function pick(forms: PluralForms, count: number, vars: Vars): string {
  const form = rules.select(count) === 'one' ? forms.one : forms.other
  const grouped = Object.fromEntries(Object.entries({ ...vars, n: count }).map(([name, value]) => [name, typeof value === 'number' ? counts.format(value) : value]))
  return fill(form, grouped)
}

/**
 * The form for the count, as {n}. The count and any other number in vars are counts, so they're grouped as US English
 * groups them (1,234); t() leaves its numbers alone, as a doc # is never grouped.
 */
export function plural(key: PluralKey, count: number, vars: Vars = {}): string {
  return pick(enUSPlurals[key], count, vars)
}

/** An app's own strings, typed over the shared catalogue's, so neither app downloads the other's */
export interface Catalogue<K extends string, P extends string> {
  /** The app's string for the key, or else the shared one, filled as t() fills it */
  t(key: StringKey | K, vars?: Vars): string
  /** The app's plural for the key, or else the shared one, as plural() picks and fills it */
  plural(key: PluralKey | P, count: number, vars?: Vars): string
  /** Whether the app's strings or the shared ones hold the key, as a problem's code is looked up by (t's keys, not plurals) */
  has(key: string): boolean
}

const own = (record: object, key: string) => Object.prototype.hasOwnProperty.call(record, key)

/**
 * A catalogue of an app's own strings and plurals, which come before the shared ones: {matter}, {matters} and
 * {matterLower} are still the firm's words, from setLabels.
 */
export function createCatalogue<K extends string, P extends string>(extra: Record<K, string>, plurals: Record<P, PluralForms>): Catalogue<K, P> {
  const has = (key: string) => own(extra, key) || own(enUS, key)
  return {
    t(key, vars = {}) {
      if (own(extra, key)) return fill(extra[key as K], vars)
      if (own(enUS, key)) return fill(enUS[key as StringKey], vars)
      throw new Error(`No string ${key}`)
    },
    plural(key, count, vars = {}) {
      if (own(plurals, key)) return pick(plurals[key as P], count, vars)
      if (own(enUSPlurals, key)) return pick(enUSPlurals[key as PluralKey], count, vars)
      throw new Error(`No plural ${key}`)
    },
    has,
  }
}

export type { PluralKey, StringKey }
