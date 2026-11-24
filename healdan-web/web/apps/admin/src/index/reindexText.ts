// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { idFrom } from '@healdan/shell'
import { plural, t } from '../strings'

/** The most different doc #s a reindex of documents takes, as the server's rule says (`422 too-many` beyond it) */
export const MOST_DOC_NUMBERS = 1000

/** One of the doc #s typed or pasted, as it was written: its number, or none for what isn't one */
export interface TypedDocNumber {
  /** Unique among them: the number, or what was written */
  key: string
  /** As it's shown, without a `#` before it */
  text: string
  docNumber: number | undefined
}

/**
 * The doc #s typed or pasted, in the order given, with commas, spaces or new lines between them, a `#` before one
 * allowed, and a version after one (`10388.2`) read as its document: each once, a repeat left out, and what isn't a whole number kept apart, as no document has it. Only whole
 * numbers are sent, so the server never sees what isn't one.
 */
export function readDocNumbers(text: string): { typed: TypedDocNumber[]; docNumbers: number[] } {
  const typed: TypedDocNumber[] = []
  const seen = new Set<string>()
  for (const word of text.split(/[\s,;]+/)) {
    const written = word.replace(/^#/, '')
    if (written === '') continue
    // A doc # with its version after a dot, as the User app's address for a version writes it (10388.2), is that document
    const docNumber = idFrom(/^\d+\.\d+$/.test(written) ? written.slice(0, written.indexOf('.')) : written)
    const key = docNumber === undefined ? `text:${written}` : String(docNumber)
    if (seen.has(key)) continue
    seen.add(key)
    typed.push({ key, text: docNumber === undefined ? written : String(docNumber), docNumber })
  }
  return { typed, docNumbers: typed.flatMap((one) => (one.docNumber === undefined ? [] : [one.docNumber])) }
}

/** The units an estimate is said in: under a minute, minutes up to an hour and a half, hours beyond */
function inUnits(seconds: number): { unit: 'short' } | { unit: 'minutes' | 'hours'; n: number } {
  if (seconds < 60) return { unit: 'short' }
  const minutes = Math.round(seconds / 60)
  return minutes < 90 ? { unit: 'minutes', n: minutes } : { unit: 'hours', n: Math.round(seconds / 3600) }
}

/** "About 12 minutes for 412 documents. Search keeps working meanwhile.", from the preview's count and estimate */
export function estimateLine(count: number, seconds: number): string {
  const documents = plural('documents', count)
  const time = inUnits(seconds)
  if (time.unit === 'short') return t('admin.reindex.estimate.short', { documents })
  return plural(time.unit === 'minutes' ? 'admin.reindex.estimate.minutes' : 'admin.reindex.estimate.hours', time.n, { documents })
}

/** "All 1,284 documents, in about 37 minutes. Search keeps finding them while they're replaced.", for Everything */
export function everythingLine(count: number, seconds: number): string {
  const documents = plural('documents', count)
  const time = inUnits(seconds)
  if (time.unit === 'short') return t('admin.reindex.everything.short', { documents })
  return plural(time.unit === 'minutes' ? 'admin.reindex.everything.minutes' : 'admin.reindex.everything.hours', time.n, { documents })
}
