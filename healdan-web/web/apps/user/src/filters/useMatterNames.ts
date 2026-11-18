// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useMemo } from 'react'
import type { components } from '@healdan/api'
import { t } from '@healdan/ui'
import { matterParts } from '../matterDisplay'
import type { Hit } from '../views/useListing'

type S = components['schemas']

/**
 * Clients' and matters' names by number, from a list's rows and the filters' options, for the chips (a number shows as
 * itself otherwise). An option gives only its number when the server can't name it to this person, so that never replaces
 * a name a row gave.
 */
export function useMatterNames(rows: Hit[], facets: S['Facets'] | null | undefined) {
  return useMemo(() => {
    const clients = new Map<string, string>()
    const matters = new Map<string, string>()
    for (const item of rows) {
      const matter = item.type === 'matter' ? null : item.matter
      if (!matter) continue
      clients.set(String(matter.client.number), t('matter.label', { number: String(matter.client.number), name: matter.client.name }))
      matters.set(matterParts(matter.display).number, matter.display)
    }
    for (const { value, displayName } of facets?.client ?? []) if (displayName !== value) clients.set(value, displayName)
    for (const { value, displayName } of facets?.matter ?? []) if (displayName !== value) matters.set(value, displayName)
    return { clients: clients as ReadonlyMap<string, string>, matters: matters as ReadonlyMap<string, string> }
  }, [rows, facets])
}
