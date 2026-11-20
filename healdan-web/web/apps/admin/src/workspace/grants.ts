// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { adminComponents } from '@healdan/api'
import { inOrder, type Permission } from './levels'

type A = adminComponents['schemas']

/**
 * A row of Who Can Use This Folder as the editor holds it: a group, or a person (by their id, P2-R15, so a person added
 * here needs no own group's id), with exactly what it grants.
 */
export interface Grant {
  /** `g` and the group's id, or `u` and the person's */
  key: string
  groupId: number | null
  userId: number | null
  person: boolean
  name: string
  /** The matter's owner's row */
  owner: boolean
  permissions: Permission[]
}

export function grantOf(row: A['AccessRow']): Grant {
  const person = row.person && row.userId !== null
  return {
    key: person ? `u${row.userId}` : `g${row.groupId}`,
    groupId: row.groupId,
    userId: row.userId,
    person,
    name: row.name,
    owner: row.owner,
    permissions: inOrder(row.permissions),
  }
}

/** What a save sends for the row: a person by their id, a group by its own */
export function bodyOf(grant: Grant): A['AccessGrant'] {
  return grant.person && grant.userId !== null ? { userId: grant.userId, permissions: grant.permissions } : { groupId: grant.groupId ?? -1, permissions: grant.permissions }
}

/** The rows as the server lists them: groups first, then people, each by name, ignoring case */
export function sortGrants(grants: readonly Grant[]): Grant[] {
  return [...grants].sort((a, b) => Number(a.person) - Number(b.person) || a.name.localeCompare(b.name, 'en', { sensitivity: 'base' }))
}

/** Whether two lists of rows grant the same, row for row */
export function sameGrants(a: readonly Grant[], b: readonly Grant[]): boolean {
  return a.length === b.length && a.every((one, i) => one.key === b[i]!.key && one.permissions.join() === b[i]!.permissions.join())
}
