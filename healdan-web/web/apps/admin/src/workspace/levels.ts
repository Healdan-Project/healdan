// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { adminComponents } from '@healdan/api'

/** One of LogicalDOC's 25 permissions, as openapi-admin.yaml names it */
export type Permission = adminComponents['schemas']['Permission']

/** LogicalDOC's 25 permissions, in its order (`Permission.values()`), which Show Every Permission lists */
export const PERMISSIONS = [
  'read',
  'download',
  'write',
  'add',
  'security',
  'immutable',
  'delete',
  'rename',
  'import',
  'export',
  'sign',
  'archive',
  'workflow',
  'calendar',
  'subscription',
  'print',
  'password',
  'move',
  'email',
  'automation',
  'store',
  'readingreq',
  'preview',
  'customid',
  'revision',
] as const satisfies readonly Permission[]

// Every permission the contract names is in the list: a new one fails to compile here
const everyOne: Exclude<Permission, (typeof PERMISSIONS)[number]> extends never ? true : never = true
void everyOne

/** The levels a folder's row is shown at (spec 8.4, P2-R8): the three named ones, and Custom for any other set */
export type FolderLevel = 'read-only' | 'read-write' | 'full-access' | 'custom'
export type NamedLevel = Exclude<FolderLevel, 'custom'>

/** The levels a row can be set to, in the order the Access menu offers them */
export const NAMED_LEVELS: readonly NamedLevel[] = ['read-only', 'read-write', 'full-access']

const READ_ONLY: readonly Permission[] = ['read', 'preview', 'download', 'print', 'email']
const LEVELS: Record<NamedLevel, readonly Permission[]> = {
  'read-only': READ_ONLY,
  'read-write': [...READ_ONLY, 'write', 'add', 'rename', 'move', 'delete'],
  'full-access': PERMISSIONS,
}

/** The permissions, each once, in LogicalDOC's order, as the server lists them */
export function inOrder(permissions: Iterable<Permission>): Permission[] {
  const set = new Set(permissions)
  return PERMISSIONS.filter((permission) => set.has(permission))
}

/** Exactly what a named level grants (`AccessLevel` in the contract), in LogicalDOC's order */
export function permissionsOf(level: NamedLevel): Permission[] {
  return inOrder(LEVELS[level])
}

/** The level a set of permissions is, by an exact match; anything else is Custom, a Store's Can See among them */
export function levelOf(permissions: readonly Permission[]): FolderLevel {
  const set = new Set(permissions)
  return NAMED_LEVELS.find((level) => LEVELS[level].length === set.size && LEVELS[level].every((one) => set.has(one))) ?? 'custom'
}
