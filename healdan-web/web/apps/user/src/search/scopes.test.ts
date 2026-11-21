// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { describe, expect, it } from 'vitest'
import { scopeOf } from './scopes'

describe('scopeOf', () => {
  it('is the scope an address names, and Documents for one it names that the API doesn\'t know, or none', () => {
    expect(scopeOf('matters')).toBe('matters')
    expect(scopeOf('emails')).toBe('emails')
    expect(scopeOf('nope')).toBe('documents')
    expect(scopeOf(undefined)).toBe('documents')
  })
})
