// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { describe, expect, it } from 'vitest'
import { ApiError } from '@healdan/api'
import { messageFor } from './errors'

// The shared catalogue's messages, for every code the User app's contract lists, are the User app's errors.test.ts.
describe("messageFor with an app's own catalogue", () => {
  const own = new Map([
    ['problems.only-here', 'A message only this app has.'],
    ['problems.not-found', 'Not found here.'],
  ])
  const catalogue = (key: string) => own.get(key)

  it("takes a code's message from the app's catalogue first", () => {
    expect(messageFor(new ApiError(409, 'only-here', 'server text', {}), catalogue)).toBe('A message only this app has.')
    expect(messageFor(new ApiError(404, 'not-found', 'server text', {}), catalogue)).toBe('Not found here.')
  })

  it("falls back to the shared catalogue for a code the app's lacks", () => {
    expect(messageFor(new ApiError(403, 'forbidden', 'x', {}), catalogue)).toBe("You don't have permission to do that.")
  })

  it('says Something went wrong for a code neither has, and for anything that is no problem', () => {
    expect(messageFor(new ApiError(500, 'no-such-code', 'secret detail', {}), catalogue)).toBe('Something went wrong.')
    expect(messageFor(new TypeError('Failed to fetch'), catalogue)).toBe('Something went wrong.')
  })

  it("takes Something went wrong from the app's catalogue when it has its own", () => {
    const unknown = (key: string) => (key === 'problems.unknown' ? 'Not that.' : undefined)
    expect(messageFor(new ApiError(500, 'no-such-code', 'x', {}), unknown)).toBe('Not that.')
    expect(messageFor(undefined, unknown)).toBe('Not that.')
  })
})
