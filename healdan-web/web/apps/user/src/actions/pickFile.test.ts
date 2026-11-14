// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { describe, expect, it } from 'vitest'
import { pickFile } from './pickFile'

const fields = () => document.body.querySelectorAll('input[type="file"]')

describe('pickFile', () => {
  it('gives the chosen file and takes its field away', async () => {
    const picked = pickFile({ accept: '.docx', label: 'File' })
    const field = fields()[0] as HTMLInputElement
    expect(field.accept).toBe('.docx')
    const file = new File(['x'], 'Lease.docx')
    Object.defineProperty(field, 'files', { value: [file] })
    field.dispatchEvent(new Event('change'))
    expect(await picked).toBe(file)
    expect(fields()).toHaveLength(0)
  })

  it('gives nothing for a cancel, and takes its field away', async () => {
    const picked = pickFile({ label: 'File' })
    fields()[0]!.dispatchEvent(new Event('cancel'))
    expect(await picked).toBeUndefined()
    expect(fields()).toHaveLength(0)
  })

  it('ends a pick whose cancel the browser never reported when the next one starts, so fields don’t pile up', async () => {
    const first = pickFile({ label: 'File' })
    const second = pickFile({ label: 'File' })
    expect(await first).toBeUndefined()
    expect(fields()).toHaveLength(1)
    fields()[0]!.dispatchEvent(new Event('cancel'))
    expect(await second).toBeUndefined()
    expect(fields()).toHaveLength(0)
  })
})
