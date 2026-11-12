// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { describe, expect, it } from 'vitest'
import { ApiError } from '@healdan/api'
import { strings, t, type StringKey } from '@healdan/ui'
import yaml from '../../../../../../healdan-api/src/main/resources/healdan/api/openapi.yaml?raw'
import { fieldErrors, isStorageUnreachable, messageFor } from './errors'

// The codes the contract lists, read from the file so a new one can't go unmapped.
const listed = /examples: \[(bad-request[^\]]*)\]/.exec(yaml)?.[1]?.split(',').map((c) => c.trim()) ?? []

// Codes the app never meets: it signs in by the web page, and only a device token gets token-only or web-session-only.
const NEVER_MET = ['sign-in-failed', 'password-expired', 'token-only', 'web-session-only']

describe('messageFor', () => {
  it('reads the contract\'s codes', () => {
    expect(listed.length).toBeGreaterThan(15)
  })

  for (const code of listed.filter((c) => !NEVER_MET.includes(c))) {
    it(`has a message for ${code}`, () => {
      expect(`problems.${code}` in strings, code).toBe(true)
      expect(messageFor(new ApiError(409, code, 'server text', {}))).toBe(t(`problems.${code}` as StringKey))
    })
  }

  it('says a 404 is not found, or not seen, and a 403 (which only comes for what the person can see) is no permission', () => {
    expect(messageFor(new ApiError(404, 'not-found', 'x', {}))).toBe("Not found, or you can't see it.")
    expect(messageFor(new ApiError(403, 'forbidden', 'x', {}))).toBe("You don't have permission to do that.")
  })

  it('says Something went wrong for an unknown code, a network failure and anything else', () => {
    expect(messageFor(new ApiError(500, 'no-such-code', 'secret detail', {}))).toBe('Something went wrong.')
    expect(messageFor(new TypeError('Failed to fetch'))).toBe('Something went wrong.')
    expect(messageFor(new SyntaxError('Unexpected end of JSON input'))).toBe('Something went wrong.')
    expect(messageFor('a string')).toBe('Something went wrong.')
    expect(messageFor(undefined)).toBe('Something went wrong.')
  })

  it('never uses the server\'s own text', () => {
    expect(messageFor(new ApiError(500, 'server-error', 'java.lang.NullPointerException at ...', {}))).not.toContain('Exception')
  })

  it('does not take a code that names a catalogue key by another route', () => {
    expect(messageFor(new ApiError(400, 'toString', 'x', {}))).toBe('Something went wrong.')
    expect(messageFor(new ApiError(400, '../common.ok', 'x', {}))).toBe('Something went wrong.')
  })
})

describe('isStorageUnreachable', () => {
  it('is true only for a 503 storage-unreachable', () => {
    expect(isStorageUnreachable(new ApiError(503, 'storage-unreachable', '', {}))).toBe(true)
    expect(isStorageUnreachable(new ApiError(503, 'server-error', '', {}))).toBe(false)
    expect(isStorageUnreachable(new TypeError('x'))).toBe(false)
  })
})

describe('fieldErrors', () => {
  const validation = (fields: unknown) => new ApiError(422, 'validation', 'x', { code: 'validation', fields })

  it('gives each field its message from the catalogue, never the server\'s text', () => {
    const errors = fieldErrors(
      validation([
        { field: 'docclass', code: 'not-an-option', detail: 'java.lang.IllegalArgumentException' },
        { field: 'comments', code: 'too-long', detail: 'x' },
      ]),
    )
    expect(Object.fromEntries(errors)).toEqual({ docclass: "That isn't one of the choices.", comments: 'That is too long.' })
  })

  it('keeps the first message of a field, and says the problem for a code it has no message for', () => {
    expect(Object.fromEntries(fieldErrors(validation([{ field: 'a', code: 'read-only', detail: '' }, { field: 'a', code: 'type', detail: '' }, { field: 'b', code: 'odd', detail: '' }])))).toEqual({
      a: "That can't be changed.",
      b: 'Some details need fixing.',
    })
  })

  it('is empty for anything that is not a validation problem with fields', () => {
    expect(fieldErrors(new ApiError(409, 'checked-out', 'x', {})).size).toBe(0)
    expect(fieldErrors(validation('nope')).size).toBe(0)
    expect(fieldErrors(new TypeError('x')).size).toBe(0)
  })
})
