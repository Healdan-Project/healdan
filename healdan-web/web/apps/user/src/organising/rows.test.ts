// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { describe, expect, it, vi } from 'vitest'
import { focusLater, pageToken, readRows, rowsPayload } from './rows'

describe('The rows a drag carries', () => {
  it('are the documents\' numbers, names and folders, and read back as they were written', () => {
    const documents = [
      { docNumber: 10431, name: 'Lease.docx', folder: { id: 7 } },
      { docNumber: 10432, name: 'Plan "A".pdf' },
    ]
    expect(readRows(rowsPayload(documents))).toEqual(documents)
  })

  it('are only this page\'s: a drag with another page\'s token, or none, moves nothing', () => {
    expect(readRows(JSON.stringify({ token: `${pageToken}x`, documents: [{ docNumber: 1, name: 'A.pdf' }] }))).toEqual([])
    expect(readRows(JSON.stringify({ documents: [{ docNumber: 1, name: 'A.pdf' }] }))).toEqual([])
    expect(readRows(JSON.stringify([{ docNumber: 1, name: 'A.pdf' }]))).toEqual([])
  })

  it('leave out anything but a number with a name, and read nothing from what is not JSON', () => {
    const documents = [{ docNumber: 1, name: 'A.pdf' }, { docNumber: '2', name: 'B.pdf' }, { docNumber: 3 }, null, 7]
    expect(readRows(JSON.stringify({ token: pageToken, documents }))).toEqual([{ docNumber: 1, name: 'A.pdf' }])
    expect(readRows(JSON.stringify({ token: pageToken, documents: 'x' }))).toEqual([])
    expect(readRows('not json')).toEqual([])
    expect(readRows('')).toEqual([])
  })
})

describe('Focusing a row later', () => {
  it('stops trying, with no fault, once the page has gone', () => {
    // A released row that stays drawn, as in a folder, keeps it trying for a second; a test's page can go in that second
    const grid = globalThis.document.createElement('div')
    grid.setAttribute('role', 'grid')
    grid.innerHTML = '<div data-row-key="d10432" tabindex="-1"></div><div data-row-key="d10433" tabindex="-1"></div>'
    globalThis.document.body.append(grid)
    vi.useFakeTimers()
    try {
      focusLater('d10432', ['d10433'])
      vi.advanceTimersByTime(100)
      expect(vi.getTimerCount()).toBe(1)
      vi.stubGlobal('document', undefined)
      expect(() => vi.runAllTimers()).not.toThrow()
      expect(vi.getTimerCount()).toBe(0)
    } finally {
      vi.unstubAllGlobals()
      vi.useRealTimers()
      grid.remove()
    }
  })
})

describe("The page's token", () => {
  it('is made without crypto.randomUUID, which a page at a plain http:// address lacks', async () => {
    vi.resetModules()
    const randomUUID = vi.spyOn(globalThis.crypto, 'randomUUID').mockImplementation(() => {
      throw new TypeError('crypto.randomUUID is not a function')
    })
    try {
      const fresh = await import('./rows')
      expect(fresh.pageToken).toMatch(/^[0-9a-f]{32}$/)
      expect(randomUUID).not.toHaveBeenCalled()
    } finally {
      randomUUID.mockRestore()
    }
  })
})
