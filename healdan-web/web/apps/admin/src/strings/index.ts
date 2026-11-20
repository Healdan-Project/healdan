// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { createCatalogue } from '@healdan/ui'
import { adminEnUS, adminPlurals } from './en-US'

const catalogue = createCatalogue(adminEnUS, adminPlurals)

/** The Admin app's string for the key, or else the shared catalogue's, filled as @healdan/ui's t() fills it */
export const t = catalogue.t
/** The Admin app's plural for the key, or else the shared catalogue's, as @healdan/ui's plural() picks and fills it */
export const plural = catalogue.plural
