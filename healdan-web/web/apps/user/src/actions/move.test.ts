// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { describe, expect, it } from 'vitest'
import { document, fakeApi, problem } from '../test/fakeApi'
import { moveDocument, moveDocuments } from './move'

describe('moveDocument', () => {
  it('posts the folder to the document\'s move', async () => {
    const api = fakeApi({ 'POST /v1/documents/10431/move': ({ body }: { body?: unknown }) => document({ docNumber: 10431, name: String(JSON.stringify(body)) }) })
    expect((await moveDocument(api, 10431, 8)).name).toBe('{"folderId":8}')
  })
})

describe('moveDocuments', () => {
  it('makes a call for each document, and reports the one that was refused', async () => {
    const api = fakeApi({ 'POST /v1/documents/*/move': document(), 'POST /v1/documents/2/move': problem(409, 'already-filed') })
    const a = { docNumber: 1, name: 'A.docx' }
    const b = { docNumber: 2, name: 'B.docx' }
    const outcome = await moveDocuments(api, [a, b, { docNumber: 3, name: 'C.docx' }], 8)
    expect(api.callsTo('POST /v1/documents/*/move').map((call) => call.path)).toEqual(['/v1/documents/1/move', '/v1/documents/2/move', '/v1/documents/3/move'])
    expect(outcome.done.map((one) => one.docNumber)).toEqual([1, 3])
    expect(outcome.refused.map((one) => one.document)).toEqual([b])
  })
})
