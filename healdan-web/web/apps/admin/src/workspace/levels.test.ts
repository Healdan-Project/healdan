// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { describe, expect, it } from 'vitest'
import { inOrder, levelOf, PERMISSIONS, permissionsOf } from './levels'

describe('Access levels', () => {
  it("lists LogicalDOC's 25 permissions in its order, as Show Every Permission lists them", () => {
    expect(PERMISSIONS).toHaveLength(25)
    expect(PERMISSIONS.slice(0, 4)).toEqual(['read', 'download', 'write', 'add'])
    expect(PERMISSIONS.at(-1)).toBe('revision')
  })

  it("gives each named level exactly spec 8.4's permissions, in LogicalDOC's order", () => {
    expect(permissionsOf('read-only')).toEqual(['read', 'download', 'print', 'email', 'preview'])
    expect(permissionsOf('read-write')).toEqual(['read', 'download', 'write', 'add', 'delete', 'rename', 'print', 'move', 'email', 'preview'])
    expect(permissionsOf('full-access')).toEqual([...PERMISSIONS])
  })

  it('names a level only by an exact match, and anything else, Can See included, Custom', () => {
    expect(levelOf(['email', 'preview', 'print', 'download', 'read'])).toBe('read-only')
    expect(levelOf(permissionsOf('read-write'))).toBe('read-write')
    expect(levelOf([...PERMISSIONS])).toBe('full-access')
    // A Store's level, which a folder shows as Custom (P2-R8)
    expect(levelOf(['read'])).toBe('custom')
    expect(levelOf([...permissionsOf('read-only'), 'write'])).toBe('custom')
    expect(levelOf([])).toBe('custom')
  })

  it("puts permissions in LogicalDOC's order, each once", () => {
    expect(inOrder(['preview', 'read', 'read', 'write'])).toEqual(['read', 'write', 'preview'])
  })
})
