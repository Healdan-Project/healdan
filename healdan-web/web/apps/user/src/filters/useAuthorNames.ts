// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useMemo } from 'react'
import type { components } from '@healdan/api'
import type { Hit } from '../views/useListing'

type S = components['schemas']

/**
 * The Authors' names by username, from a list's rows and the Author filter's options, for the chips (a username shows
 * as itself otherwise). A folder's list and a search's results both use it.
 */
export function useAuthorNames(rows: Hit[], facets: S['Facets'] | null | undefined): ReadonlyMap<string, string> {
  return useMemo(() => {
    const names = new Map<string, string>()
    for (const item of rows) if (item.type === 'document' && item.author) names.set(item.author.username, item.author.displayName)
    for (const option of facets?.author ?? []) names.set(option.value, option.displayName)
    return names
  }, [rows, facets])
}
