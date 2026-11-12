// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { ApiError } from '@healdan/api'
import { describe, expect, it } from 'vitest'
import { problem } from '../test/fakeApi'
import { extensionOf, renameRefusal } from './rename'

const refused = (field = 'name', code = 'invalid') =>
  new ApiError(422, 'validation', 'A server sentence that must not be shown raw.', { code: 'validation', fields: [{ field, code }] })

describe('extensionOf', () => {
  it('is what follows the last dot, with something before it', () => {
    expect(extensionOf('Lease.docx')).toBe('docx')
    expect(extensionOf('a.b.pdf')).toBe('pdf')
    expect(extensionOf('Lease')).toBeUndefined()
    expect(extensionOf('.hidden')).toBeUndefined()
    expect(extensionOf('Lease.')).toBeUndefined()
  })
})

describe('renameRefusal', () => {
  it('says what to do about another extension', () => {
    expect(renameRefusal(refused('name', 'extension'), 'Lease.docx')).toBe("The name has to end in .docx, as the document's does.")
    expect(renameRefusal(refused('name', 'extension'), 'Lease')).toBe("The name can't have an extension, as the document's has none.")
  })

  it('says any other refusal of the name from the catalogue', () => {
    expect(renameRefusal(refused('name', 'too-long'), 'Lease')).toBe('That is too long.')
    // The extension the document keeps counts, which the person may not have typed
    expect(renameRefusal(refused('name', 'too-long'), 'Lease.docx')).toBe("With its .docx, the name can't be longer than 255 characters.")
    // A slash is not the extension's business, whatever the name ends in: the app takes the server's code, not its own guess
    expect(renameRefusal(refused(), 'Lease.docx')).toBe("That name isn't allowed.")
    expect(renameRefusal(problem(409, 'locked'), 'Lease.docx')).toBe("It's locked.")
    expect(renameRefusal(new Error('boom'), 'Lease.docx')).toBe('Something went wrong.')
  })
})
