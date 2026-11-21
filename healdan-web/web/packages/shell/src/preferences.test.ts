// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { afterEach, describe, expect, it, vi } from 'vitest'
import { preferences } from './preferences'

afterEach(() => {
  vi.restoreAllMocks()
  window.localStorage.clear()
})

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

  it('keeps and reads JSON under its prefix', () => {
    const user = preferences('healdan.')
    user.keepJson('columns.folder', { v: 1 })
    expect(window.localStorage.getItem('healdan.columns.folder')).toBe('{"v":1}')
    expect(user.readJson('columns.folder')).toEqual({ v: 1 })
  })

  it("reads nothing from text that isn't JSON", () => {
    window.localStorage.setItem('healdan.columns.folder', '{v:')
    expect(preferences('healdan.').readJson('columns.folder')).toBeUndefined()
  })

  it('reads and keeps no JSON when storage is refused, and throws nothing', () => {
    const user = preferences('healdan.')
    vi.spyOn(window, 'localStorage', 'get').mockImplementation(() => {
      throw new DOMException('Access is denied for this document.', 'SecurityError')
    })
    expect(user.readJson('columns.folder')).toBeUndefined()
    expect(() => user.keepJson('columns.folder', { v: 1 })).not.toThrow()
    vi.restoreAllMocks()
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('The quota has been exceeded.', 'QuotaExceededError')
    })
    expect(() => user.keepJson('columns.folder', { v: 1 })).not.toThrow()
  })
})
