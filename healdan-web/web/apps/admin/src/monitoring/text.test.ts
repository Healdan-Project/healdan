// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { describe, expect, it } from 'vitest'
import type { adminComponents } from '@healdan/api'
import { kindLabel } from './text'

describe("The Audit Trail's kinds", () => {
  it('are named in the catalogue', () => {
    expect(kindLabel('sign-in')).toBe('Sign-In')
    expect(kindLabel('administration')).toBe('Administration')
  })

  it("fall back to the kind itself for one the catalogue doesn't know, rather than throwing (P6-R17)", () => {
    expect(kindLabel('search-index' as adminComponents['schemas']['AuditKind'])).toBe('search-index')
  })
})
