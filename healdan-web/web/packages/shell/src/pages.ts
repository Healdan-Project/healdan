// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { components } from '@healdan/api'

// A list from the API: openapi-typescript types it as Page & { items?: T[] }, whose items it can't tell from unknown[].
export type PageOf<T> = Omit<components['schemas']['Page'], 'items'> & { items: T[] }
