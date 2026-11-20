// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { describe, expect, it } from 'vitest'
import { t } from '@healdan/ui'
import { document, fakeApi } from '../test/fakeApi'
import { moveDocument, planMove } from './move'

describe('moveDocument', () => {
  it('posts the folder to the document\'s move', async () => {
    const api = fakeApi({ 'POST /v1/documents/10431/move': ({ body }: { body?: unknown }) => document({ docNumber: 10431, name: String(JSON.stringify(body)) }) })
    expect((await moveDocument(api, 10431, 8)).name).toBe('{"folderId":8}')
  })
})

describe('planMove', () => {
  it("refuses a matter's own folder, which an administrator moves on the matter's page in the Admin app", () => {
    const workspace = { id: 110, name: '1001.1 – Charter Dispute', parentId: 4, matterId: 11, canMove: false, canDelete: false, kind: 'matter' as const }
    const plan = planMove({ documents: [], folders: [workspace] }, { id: 5, kind: 'store', matterId: null }, true)
    expect(plan.folders).toEqual([])
    expect(plan.refused.map((one) => t(one.reason))).toEqual([
      "A matter's own folder is moved by an administrator, with Move to Another Store on the matter's page in the Admin app.",
    ])
  })
})
