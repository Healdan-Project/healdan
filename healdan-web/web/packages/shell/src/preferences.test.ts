// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { afterEach, describe, expect, it } from 'vitest'
import { preferences } from './preferences'

afterEach(() => window.localStorage.clear())

// Refused and full storage are the User app's preferences.test.ts, through the same functions.
describe('preferences', () => {
  it("keeps each app's numbers under its own prefix, apart from another's", () => {
    const user = preferences('healdan.')
    const other = preferences('healdan.other.')
    user.keepNumber('pane.left', 300)
    other.keepNumber('pane.left', 220)
    expect(window.localStorage.getItem('healdan.pane.left')).toBe('300')
    expect(window.localStorage.getItem('healdan.other.pane.left')).toBe('220')
    expect(user.readNumber('pane.left')).toBe(300)
    expect(other.readNumber('pane.left')).toBe(220)
    other.forget('pane.left')
    expect(other.readNumber('pane.left')).toBeUndefined()
    expect(user.readNumber('pane.left')).toBe(300)
  })
})
