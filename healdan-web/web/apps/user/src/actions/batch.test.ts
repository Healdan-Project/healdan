// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { describe, expect, it } from 'vitest'
import { problem } from '../test/fakeApi'
import { eachDocument, refusalLines } from './batch'

const a = { docNumber: 1, name: 'A.docx' }
const b = { docNumber: 2, name: 'B.docx' }
const c = { docNumber: 3, name: 'C.docx' }

describe('eachDocument', () => {
  it('calls one document after another, and keeps going past a refusal', async () => {
    const order: string[] = []
    const outcome = await eachDocument([a, b, c], async (document) => {
      order.push(`start ${document.docNumber}`)
      await Promise.resolve()
      order.push(`end ${document.docNumber}`)
      if (document === b) throw problem(409, 'checked-out')
    })
    // One at a time, so the server never has two of them at once
    expect(order).toEqual(['start 1', 'end 1', 'start 2', 'end 2', 'start 3', 'end 3'])
    expect(outcome.done).toEqual([a, c])
    expect(outcome.refused.map((one) => [one.document, (one.error as { code: string }).code])).toEqual([[b, 'checked-out']])
  })
})

describe('refusalLines', () => {
  it('say each refused document and why, from the catalogue', () => {
    const lines = refusalLines([{ document: b, error: problem(409, 'locked') }, { document: c, error: new Error('boom') }], undefined)
    expect(lines).toEqual([
      { key: 2, name: 'B.docx', reason: "It's locked." },
      { key: 3, name: 'C.docx', reason: 'Something went wrong.' },
    ])
  })
})
