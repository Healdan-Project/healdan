// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { renderHook } from '@testing-library/react'
import type { ReactNode } from 'react'
import { describe, expect, it } from 'vitest'
import { ApiError } from '@healdan/api'
import { MessagesProvider } from '@healdan/shell'
import { problem } from '../test/fakeApi'
import { problemLookup } from './index'
import { ruleSentence, useRefusalFor } from './refusals'

const SENTENCE = 'Client number 1001 is already used.'

function refusalFor() {
  const wrapper = ({ children }: { children: ReactNode }) => <MessagesProvider lookup={problemLookup}>{children}</MessagesProvider>
  return renderHook(() => useRefusalFor(), { wrapper }).result.current
}

describe('What an Admin form says of a refusal', () => {
  it("says a 409 refused's own sentence, as the rule words it", () => {
    expect(ruleSentence(problem(409, 'refused', SENTENCE))).toBe(SENTENCE)
    expect(refusalFor()(problem(409, 'refused', SENTENCE))).toBe(SENTENCE)
  })

  it('says a check\'s problem by its own message, as New Matter\'s check words it', () => {
    const found = { code: 'store-unreadable', message: "Everyone can't read the Store Regional Office.", fixAction: 'grant-store-read' }
    expect(ruleSentence(found)).toBe(found.message)
    expect(refusalFor()(found)).toBe(found.message)
  })

  it("says the catalogue's message for a refusal with no sentence", () => {
    expect(ruleSentence(problem(409, 'refused', '  '))).toBeUndefined()
    expect(refusalFor()(problem(409, 'refused', ''))).toBe('That was refused.')
  })

  it("says the catalogue's message for every other code, never the server's text", () => {
    const say = refusalFor()
    expect(say(problem(422, 'validation'))).toBe('Some details need fixing.')
    expect(say(problem(404, 'not-found'))).toBe("Not found, or you can't see it.")
    expect(say(problem(500, 'server-error'))).toBe('Something went wrong.')
    expect(say(new ApiError(409, 'name-taken', 'A server sentence that must not be shown raw.', {}))).toBe("There's already one with that name here.")
    for (const other of [problem(422, 'validation'), problem(500, 'server-error'), problem(403, 'admins-only')]) expect(ruleSentence(other)).toBeUndefined()
  })

  it('takes no ordinary error, and nothing that only looks like a problem, for a sentence', () => {
    const say = refusalFor()
    expect(ruleSentence(new Error('Failed to fetch'))).toBeUndefined()
    expect(say(new Error('Failed to fetch'))).toBe('Something went wrong.')
    expect(ruleSentence({ message: 'No code' })).toBeUndefined()
    expect(ruleSentence({ code: 'store-unreadable', message: ' ' })).toBeUndefined()
    expect(ruleSentence(undefined)).toBeUndefined()
    expect(ruleSentence('refused')).toBeUndefined()
  })
})
