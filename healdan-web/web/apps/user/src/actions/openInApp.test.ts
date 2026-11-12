// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { describe, expect, it } from 'vitest'
import { handlesInApp, healdanLink } from './openInApp'

// The cases of the old UI's HealdanOpenInAppTest, which this ports
describe('handlesInApp', () => {
  it('offers PDFs in any case', () => {
    expect(handlesInApp('Signed NDA.pdf')).toBe(true)
    expect(handlesInApp('SCAN.PDF')).toBe(true)
  })

  it('still offers the office kinds', () => {
    expect(handlesInApp('Lease.docx')).toBe(true)
    expect(handlesInApp('Plan.odg')).toBe(true)
  })

  it('offers every kind the helper opens', () => {
    for (const extension of ['doc', 'docx', 'docm', 'dotx', 'dotm', 'rtf', 'odt', 'ott', 'xls', 'xlsx', 'xlsm', 'ods', 'ppt', 'pptx', 'pptm', 'odp', 'odg', 'pdf'])
      expect(handlesInApp(`File.${extension}`), extension).toBe(true)
  })

  it('offers nothing else', () => {
    expect(handlesInApp('Photo.png')).toBe(false)
    expect(handlesInApp('pdf')).toBe(false)
    expect(handlesInApp('a.')).toBe(false)
    expect(handlesInApp(undefined)).toBe(false)
    expect(handlesInApp('Message.eml')).toBe(false)
  })
})

describe('healdanLink', () => {
  it('is healdan://open/<doc #>?server=<host>, with no credentials', () => {
    expect(healdanLink(10388, 'healdan.example.test')).toBe('healdan://open/10388?server=healdan.example.test')
    expect(healdanLink(10388, 'localhost:8080')).toBe('healdan://open/10388?server=localhost:8080')
  })
})
