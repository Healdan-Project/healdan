// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { describe, expect, it } from 'vitest'
import { readingOf } from './AuditList'

const at = (over: Partial<Parameters<typeof readingOf>[0]>) => readingOf({ isLoaded: true, isStale: false, atEnd: false, hasMore: false, ...over })

describe("Where the Audit Trail's reading stands", () => {
  it('says it is loading, with Show More pending, between a page that came back short and the next the list asks for itself', () => {
    // A page has come back short with a cursor, and the list hasn't asked for the next yet: neither more is offered nor
    // is one on its way, but the end hasn't come
    expect(at({})).toEqual({ offering: true, following: true, empty: 'loading' })
  })

  it('offers Show More once the list has stopped by itself, saying nothing has been found yet', () => {
    expect(at({ hasMore: true })).toEqual({ offering: true, following: false, empty: 'noneYet' })
  })

  it('says nothing is there at the end, with no Show More', () => {
    expect(at({ atEnd: true })).toEqual({ offering: false, following: false, empty: 'none' })
  })

  it('says it is loading, with no Show More, before the first page and while a new filter loads', () => {
    expect(at({ isLoaded: false })).toEqual({ offering: false, following: false, empty: 'loading' })
    expect(at({ isStale: true })).toEqual({ offering: false, following: false, empty: 'loading' })
  })
})
