// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { ApiError } from '@healdan/api'
import { describe, expect, it } from 'vitest'
import { folder, problem } from '../test/fakeApi'
import { extensionOf, folderRenameRefusal, isTopLevel, renameRefusal, whyNotRename } from './rename'

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

const store = { id: 2, name: 'Smoke', kind: 'store' as const }
const workspace = { id: 110, name: '1001.1 – Charter Dispute', kind: 'matter' as const }
const top = folder({ id: 111, name: 'Documents', path: [store, workspace, { id: 111, name: 'Documents', kind: 'folder' }] })
const inside = folder({ id: 112, name: 'Letters', path: [store, workspace, { id: 111, name: 'Documents', kind: 'folder' }, { id: 112, name: 'Letters', kind: 'folder' }] })

describe('whyNotRename', () => {
  it('says nothing when the server says the folder can be renamed', () => {
    expect(whyNotRename(inside, false)).toBeUndefined()
  })
  it("says a matter's own folder takes the matter's name", () => {
    expect(whyNotRename(folder({ kind: 'matter', canRename: false, path: [store, workspace] }), true)).toBe(
      "A matter's own folder takes the matter's name: an administrator changes it with Edit Matter… in the Classic Interface.",
    )
  })
  it("says a matter's top-level folders are for administrators, to someone who isn't one", () => {
    expect(isTopLevel(top)).toBe(true)
    expect(isTopLevel(inside)).toBe(false)
    expect(whyNotRename({ ...top, canRename: false }, false)).toBe("Only administrators can rename a matter's top-level folders.")
  })
  it('says it is a permission otherwise', () => {
    expect(whyNotRename({ ...top, canRename: false }, true)).toBe("You don't have permission to rename this folder.")
    expect(whyNotRename({ ...inside, canRename: false }, false)).toBe("You don't have permission to rename this folder.")
  })
})

describe('folderRenameRefusal', () => {
  const refused = (status: number, code: string, body: object = {}) => new ApiError(status, code, 'A server sentence.', { status, code, ...body })
  it('words each refusal from the catalogue, never the server', () => {
    expect(folderRenameRefusal(refused(409, 'name-taken'), 'Letters')).toBe("There's already a folder called Letters here.")
    expect(folderRenameRefusal(refused(403, 'admins-only'), 'X')).toBe("Only administrators can rename a matter's top-level folders.")
    expect(folderRenameRefusal(refused(409, 'not-renamable'), 'X')).toContain('Edit Matter…')
    expect(folderRenameRefusal(refused(403, 'forbidden'), 'X')).toBe("You don't have permission to rename this folder.")
    const fields = [{ field: 'name', code: 'invalid', detail: 'A server sentence.' }]
    expect(folderRenameRefusal(refused(422, 'validation', { fields }), 'X')).toBe("That name isn't allowed.")
    expect(folderRenameRefusal(refused(409, 'refused'), 'X')).toBe('That was refused.')
  })
})
