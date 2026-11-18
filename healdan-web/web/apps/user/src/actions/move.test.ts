// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { describe, expect, it } from 'vitest'
import { document, fakeApi } from '../test/fakeApi'
import { moveDocument } from './move'

describe('moveDocument', () => {
  it('posts the folder to the document\'s move', async () => {
    const api = fakeApi({ 'POST /v1/documents/10431/move': ({ body }: { body?: unknown }) => document({ docNumber: 10431, name: String(JSON.stringify(body)) }) })
    expect((await moveDocument(api, 10431, 8)).name).toBe('{"folderId":8}')
  })
})
