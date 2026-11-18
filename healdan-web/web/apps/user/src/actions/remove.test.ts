// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { describe, expect, it } from 'vitest'
import { fakeApi, trashItem } from '../test/fakeApi'
import { removeDocument } from './remove'

describe('removeDocument', () => {
  it('deletes the document, which moves it to the Trash, and answers the Trash item', async () => {
    const api = fakeApi({ 'DELETE /v1/documents/10431': trashItem({ id: 7, docNumber: 10431 }) })
    const item = await removeDocument(api, 10431)
    expect(api.callsTo('DELETE /v1/documents/10431')).toHaveLength(1)
    expect(item.id).toBe(7)
  })
})
