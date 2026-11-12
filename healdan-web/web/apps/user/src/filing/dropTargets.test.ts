// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { describe, expect, it } from 'vitest'
import { carriesFiles, carriesRows, droppedFiles, refusalOf, ROWS_TYPE } from './dropTargets'

describe('dropTargets', () => {
  it('tells files from the app\'s own rows by the data transfer\'s types', () => {
    expect(carriesFiles(['Files'])).toBe(true)
    expect(carriesFiles(['text/uri-list', 'text/plain'])).toBe(false)
    expect(carriesRows([ROWS_TYPE])).toBe(true)
    expect(carriesRows(['Files'])).toBe(false)
    expect(ROWS_TYPE).toBe('application/x-healdan-documents')
  })

  it('refuses a matter\'s top level, a search folder and a Store, which hold no documents', () => {
    expect(refusalOf({ id: 1, kind: 'matter' })).toBe('filing.chooseFolder')
    expect(refusalOf({ id: 2, kind: 'searchFolder' })).toBe('filing.chooseFolder')
    expect(refusalOf({ id: 3, kind: 'store' })).toBe('filing.chooseFolder')
  })

  it('refuses a folder the person may not file into, and takes any other', () => {
    expect(refusalOf({ id: 4, kind: 'folder', canFile: false })).toBe('filing.noWrite')
    expect(refusalOf({ id: 4, kind: 'folder', canFile: true })).toBeUndefined()
    // A folder in the tree is known only by its kind: the server decides the rest
    expect(refusalOf({ id: 5, kind: 'folder' })).toBeUndefined()
  })

  it('leaves out a folder dragged from Finder, which its item says is one, and counts it', () => {
    const files = [new File(['a'], 'Lease.docx'), new File([''], 'Scans'), new File(['b'], 'Plan.pdf')]
    const item = (isDirectory: boolean) => ({ kind: 'file', webkitGetAsEntry: () => ({ isDirectory }) })
    const items = [item(false), item(true), item(false)] as unknown as DataTransferItemList
    expect(droppedFiles({ files: files as unknown as FileList, items })).toEqual({ files: [files[0], files[2]], folders: 1 })
    // Without items to say, every file is taken
    expect(droppedFiles({ files: files as unknown as FileList })).toEqual({ files, folders: 0 })
  })
})
