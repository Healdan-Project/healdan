// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { createCatalogue } from '@healdan/ui'
import { adminEnUS, adminPlurals } from './en-US'

const catalogue = createCatalogue(adminEnUS, adminPlurals)

/** The Admin app's string for the key, or else the shared catalogue's, filled as @healdan/ui's t() fills it */
export const t = catalogue.t
/** The Admin app's plural for the key, or else the shared catalogue's, as @healdan/ui's plural() picks and fills it */
export const plural = catalogue.plural
/** Whether the Admin app's catalogue, or the shared one, holds a string for the key */
export const hasString = catalogue.has

/**
 * The messages for the problems the app's own catalogue holds (only those, so the shared catalogue answers the rest), in
 * the form @healdan/shell's messageFor and MessagesProvider take. `extra` is the app's own strings.
 */
export function lookupFor(extra: object, text: (key: never) => string): (key: string) => string | undefined {
  return (key) => (key.startsWith('problems.') && Object.hasOwn(extra, key) ? text(key as never) : undefined)
}

export const problemLookup = lookupFor(adminEnUS, catalogue.t)
