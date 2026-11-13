// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { QueryClient } from '@tanstack/react-query'
import { describe, expect, it } from 'vitest'
import { keys } from '../queries'
import { markDocumentListsStale } from './refresh'

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
