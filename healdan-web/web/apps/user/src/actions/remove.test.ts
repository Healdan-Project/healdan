// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { describe, expect, it } from 'vitest'
import { fakeApi, problem, trashItem } from '../test/fakeApi'
import { removeDocument, removeDocuments } from './remove'

describe('removeDocument', () => {
  it('deletes the document, which moves it to the Trash, and answers the Trash item', async () => {
    const api = fakeApi({ 'DELETE /v1/documents/10431': trashItem({ id: 7, docNumber: 10431 }) })
    const item = await removeDocument(api, 10431)
    expect(api.callsTo('DELETE /v1/documents/10431')).toHaveLength(1)
    expect(item.id).toBe(7)
  })
})

describe('removeDocuments', () => {
  it('makes a call for each document, and reports the one that was refused', async () => {
    const api = fakeApi({ 'DELETE /v1/documents/*': undefined, 'DELETE /v1/documents/2': problem(409, 'checked-out') })
    const b = { docNumber: 2, name: 'B.docx' }
    const outcome = await removeDocuments(api, [{ docNumber: 1, name: 'A.docx' }, b])
    expect(outcome.done.map((one) => one.docNumber)).toEqual([1])
    expect(outcome.refused.map((one) => one.document)).toEqual([b])
  })
})
