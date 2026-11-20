// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { adminComponents } from '@healdan/api'
import { inOrder, type Permission } from './levels'

type A = adminComponents['schemas']

/**
 * A row of Who Can Use This Folder as the editor holds it: a group, or a person, with exactly what it grants. A row the
 * server listed has its group's id, a person's own group's included; a person added here has only their id (P2-R15).
 */
export interface Grant {
  /** `g` and the group's id, or `u` and the person's */
  key: string
  /** The group, or the person's own group; null only for a person added here */
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

/**
 * What a save sends for the row: a row the server listed by its group's id, as it came, so a person whose account has
 * gone since doesn't stop the save; a person added here by their id
 */
export function bodyOf(grant: Grant): A['AccessGrant'] {
  if (grant.groupId !== null) return { groupId: grant.groupId, permissions: grant.permissions }
  if (grant.userId !== null) return { userId: grant.userId, permissions: grant.permissions }
  throw new Error(`The row ${grant.key} names neither a group nor a person`)
}

/** The rows as the server lists them: groups first, then people, each by name, ignoring case */
export function sortGrants(grants: readonly Grant[]): Grant[] {
  return [...grants].sort((a, b) => Number(a.person) - Number(b.person) || a.name.localeCompare(b.name, 'en', { sensitivity: 'base' }))
}

/** Whether two lists of rows grant the same, row for row */
export function sameGrants(a: readonly Grant[], b: readonly Grant[]): boolean {
  return a.length === b.length && a.every((one, i) => one.key === b[i]!.key && one.permissions.join() === b[i]!.permissions.join())
}
