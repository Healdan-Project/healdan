// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { describe, expect, it } from 'vitest'
import { strings } from '@healdan/ui'
import { adminEnUS, adminPlurals } from './en-US'
import { plural, t } from './index'

describe("The Admin app's strings", () => {
  it('are all its own, under admin., but for the messages of problems only the admin API answers', () => {
    const others = Object.keys(adminEnUS).filter((key) => !key.startsWith('admin.'))
    expect(others.filter((key) => !key.startsWith('problems.'))).toEqual([])
    // None says again, or differently, what the shared catalogue says
    expect(others.filter((key) => Object.hasOwn(strings, key))).toEqual([])
    expect(Object.keys(adminPlurals).filter((key) => !key.startsWith('admin.'))).toEqual([])
  })

  // scripts/check-dist.mjs looks for this key in the User app's build, which never holds the Admin app's code
  it("hold the marker the User app's build check looks for", () => {
    expect(t('admin.dashboard.title')).toBe('Dashboard')
  })

  it("name the app, and still reach the shared catalogue's strings and plurals", () => {
    expect(t('admin.app.name')).toBe('Healdan Admin')
    expect(t('common.cancel')).toBe('Cancel')
    expect(plural('documents', 1234)).toBe('1,234 documents')
  })
})
