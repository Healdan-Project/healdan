// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { describe, expect, it } from 'vitest'
import { searchIndexDocument, searchIndexJob } from '../test/fakeApi'
import { docNumberHeader, documentWords, jobTitle, outcomeWords, stateWords, timeLeftWords } from './text'

describe("The Search Index's words", () => {
  it('say about how long a reindex has left: under a minute, minutes up to an hour and a half, then hours', () => {
    expect(timeLeftWords(1)).toBe('less than a minute left')
    expect(timeLeftWords(59)).toBe('less than a minute left')
    expect(timeLeftWords(60)).toBe('about a minute left')
    expect(timeLeftWords(61)).toBe('about 2 minutes left')
    expect(timeLeftWords(360)).toBe('about 6 minutes left')
    expect(timeLeftWords(89 * 60)).toBe('about 89 minutes left')
    expect(timeLeftWords(90 * 60)).toBe('about 2 hours left')
    expect(timeLeftWords(5 * 3600)).toBe('about 5 hours left')
  })

  it('name a document by its doc # and file name, or its file name alone', () => {
    expect(documentWords(searchIndexDocument())).toBe('# 10412 Witness Statement.pdf')
    expect(documentWords(searchIndexDocument({ docNumber: null }))).toBe('Witness Statement.pdf')
    expect(docNumberHeader(searchIndexDocument({ docNumber: null }))).toBe('No doc #')
  })

  it("title a reindex's card by its state, and say how it went, leaving out what's none", () => {
    expect(jobTitle(searchIndexJob())).toBe('Reindexing Matter 1001.1 Charter Dispute')
    expect(jobTitle(searchIndexJob({ state: 'waiting' }))).toBe('Waiting to Reindex Matter 1001.1 Charter Dispute')
    expect(jobTitle(searchIndexJob({ state: 'done' }))).toBe('Reindexed Matter 1001.1 Charter Dispute')
    expect(jobTitle(searchIndexJob({ state: 'cancelled' }))).toBe('Cancelled Reindexing Matter 1001.1 Charter Dispute')
    expect(outcomeWords(searchIndexJob({ done: 1234, failed: 0, skipped: 0 }))).toBe('1,234 reindexed')
    expect(outcomeWords(searchIndexJob({ done: 0, failed: 1, skipped: 1 }))).toBe("0 reindexed, 1 couldn't be indexed, 1 skipped")
    expect(stateWords(searchIndexJob({ cancelling: true }))).toBe('Stopping')
  })
})
