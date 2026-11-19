// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { PluralForms } from '@healdan/ui'

// The Admin app's own strings, over @healdan/ui's shared catalogue, so the User app never downloads them. US English;
// labels in Title Case. Every key starts admin., and {matter}, {matters} and {matterLower} are the firm's words, as in
// the shared catalogue.
export const adminEnUS = {
  'admin.app.name': 'Healdan Admin',
  // scripts/check-dist.mjs looks for this key in the User app's build, which never holds the Admin app's code
  'admin.dashboard.title': 'Dashboard',
} as const satisfies Record<`admin.${string}`, string>

export const adminPlurals = {} as const satisfies Record<`admin.${string}`, PluralForms>
