// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { QueryClient } from '@tanstack/react-query'
import { describe, expect, it } from 'vitest'
import { keys } from '../queries'
import { messageView } from '../test/fakeApi'
import { invalidateDocumentLists, markDocumentListsStale } from './refresh'

describe('invalidateDocumentLists', () => {
  // Each read of a message reads the whole email on the server, so only a message that shows a changed document is read again
  it('marks a filed email\'s message stale when it shows a changed document as an attachment\'s copy, and no other', async () => {
    const client = new QueryClient()
    const message = messageView()
    const savedAs = (docNumber: number | null) => ({ ...message, attachments: message.attachments.map((one, i) => (i === 0 ? { ...one, docNumber } : one)) })
    client.setQueryData(keys.message(10500, '1.0'), savedAs(10432))
    client.setQueryData(keys.message(10501, '1.0'), savedAs(10999))
    client.setQueryData(keys.message(10502, '1.0'), savedAs(null))

    await invalidateDocumentLists(client, [10432, 10433])
    expect(client.getQueryState(keys.message(10500, '1.0'))?.isInvalidated).toBe(true)
    expect(client.getQueryState(keys.message(10501, '1.0'))?.isInvalidated).toBe(false)
    expect(client.getQueryState(keys.message(10502, '1.0'))?.isInvalidated).toBe(false)
  })

  it('leaves every message alone when no document is named', async () => {
    const client = new QueryClient()
    const message = messageView()
    client.setQueryData(keys.message(10500, '1.0'), { ...message, attachments: [{ ...message.attachments[0]!, docNumber: 10432 }] })

    await invalidateDocumentLists(client)
    expect(client.getQueryState(keys.message(10500, '1.0'))?.isInvalidated).toBe(false)
  })
})

describe('markDocumentListsStale', () => {
  // A tree with many folders open would ask for each on the return after Open in App
  it("marks what shows a document's state stale, but not My Matters' folders", () => {
    const client = new QueryClient()
    const marked = [keys.document(10431), keys.versions(10431), keys.children(7, {}), keys.recentDocuments, keys.recentMatters, keys.search({ q: 'charter' })]
    for (const key of [...marked, keys.subfolders(7)]) client.setQueryData(key, {})

    markDocumentListsStale(client)
    expect(marked.map((key) => client.getQueryState(key)?.isInvalidated)).toEqual(marked.map(() => true))
    expect(client.getQueryState(keys.subfolders(7))?.isInvalidated).toBe(false)
  })
})
