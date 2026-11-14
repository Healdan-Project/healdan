// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { afterEach, describe, expect, it, vi } from 'vitest'
import { forget, keepNumber, readNumber } from './preferences'

afterEach(() => {
  vi.restoreAllMocks()
  window.localStorage.clear()
})

describe('preferences', () => {
  it('keeps a number under the app\'s own prefix, reads it back and forgets it', () => {
    keepNumber('pane.left', 300)
    expect(window.localStorage.getItem('healdan.pane.left')).toBe('300')
    expect(readNumber('pane.left')).toBe(300)
    forget('pane.left')
    expect(window.localStorage.getItem('healdan.pane.left')).toBeNull()
    expect(readNumber('pane.left')).toBeUndefined()
  })

  it('reads nothing from a value that is not a number', () => {
    for (const text of ['', '  ', 'wide', 'NaN', 'Infinity', '12px']) {
      window.localStorage.setItem('healdan.pane.left', text)
      expect(readNumber('pane.left'), text).toBeUndefined()
    }
  })

  it('reads nothing, and throws nothing, when storage is refused', () => {
    const refused = () => {
      throw new DOMException('The operation is insecure.', 'SecurityError')
    }
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(refused)
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(refused)
    vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(refused)
    expect(readNumber('pane.left')).toBeUndefined()
    expect(() => keepNumber('pane.left', 300)).not.toThrow()
    expect(() => forget('pane.left')).not.toThrow()
  })

  it('copes with a browser whose storage itself is refused, as some are with cookies blocked', () => {
    vi.spyOn(window, 'localStorage', 'get').mockImplementation(() => {
      throw new DOMException('Access is denied for this document.', 'SecurityError')
    })
    expect(readNumber('pane.left')).toBeUndefined()
    expect(() => keepNumber('pane.left', 300)).not.toThrow()
    expect(() => forget('pane.left')).not.toThrow()
  })

  it('keeps nothing when the quota is full, and says what was kept before', () => {
    keepNumber('pane.left', 300)
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('The quota has been exceeded.', 'QuotaExceededError')
    })
    keepNumber('pane.left', 360)
    expect(readNumber('pane.left')).toBe(300)
  })
})
