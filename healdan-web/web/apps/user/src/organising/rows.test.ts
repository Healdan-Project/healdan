// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { describe, expect, it } from 'vitest'
import { pageToken, readRows, rowsPayload } from './rows'

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
