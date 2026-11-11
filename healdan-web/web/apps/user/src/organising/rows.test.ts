// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { describe, expect, it } from 'vitest'
import { readRows, rowsPayload } from './rows'

describe('The rows a drag carries', () => {
  it('are the documents\' numbers and names, and read back as they were written', () => {
    const documents = [
      { docNumber: 10431, name: 'Lease.docx' },
      { docNumber: 10432, name: 'Plan "A".pdf' },
    ]
    expect(readRows(rowsPayload(documents))).toEqual(documents)
  })

  it('leave out anything but a number with a name, and read nothing from what is not JSON', () => {
    expect(readRows('[{"docNumber":1,"name":"A.pdf"},{"docNumber":"2","name":"B.pdf"},{"docNumber":3},null,7]')).toEqual([{ docNumber: 1, name: 'A.pdf' }])
    expect(readRows('{"docNumber":1,"name":"A.pdf"}')).toEqual([])
    expect(readRows('not json')).toEqual([])
    expect(readRows('')).toEqual([])
  })
})
