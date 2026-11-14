// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { describe, expect, it } from 'vitest'
import { clientIcon, fileIcon, folderIcon, matterIcon } from './kindIcons'
import { recentKinds, scopeIcons, searchScopes } from './search/scopes'

// The extension decides the icon and the colour; LibreOffice's formats take their Office counterparts' (owner, 2026-11-14)
const groups: [string, string[], ReturnType<typeof fileIcon>][] = [
  ['Word', ['docx', 'docm', 'dotx', 'dotm'], { name: 'fileDocx', kind: 'wordProcessing' }],
  ['older Word', ['doc', 'dot'], { name: 'fileDoc', kind: 'wordProcessing' }],
  ['RTF and LibreOffice Writer', ['rtf', 'odt', 'ott'], { name: 'document', kind: 'wordProcessing' }],
  ['Excel', ['xlsx', 'xlsm', 'xltx', 'xls'], { name: 'fileXls', kind: 'spreadsheet' }],
  ['CSV', ['csv'], { name: 'fileCsv', kind: 'spreadsheet' }],
  ['LibreOffice Calc', ['ods', 'ots'], { name: 'spreadsheet', kind: 'spreadsheet' }],
  ['PowerPoint', ['pptx', 'pptm', 'ppsx', 'potx', 'ppt'], { name: 'filePpt', kind: 'presentation' }],
  ['LibreOffice Impress', ['odp', 'otp'], { name: 'presentation', kind: 'presentation' }],
  ['PDF', ['pdf'], { name: 'filePdf', kind: 'pdf' }],
  ['email', ['eml', 'msg'], { name: 'email', kind: 'email' }],
  ['picture', ['png', 'jpg', 'jpeg', 'gif', 'tif', 'tiff', 'bmp', 'webp', 'heic'], { name: 'picture', kind: 'picture' }],
]

describe('fileIcon', () => {
  it.each(groups)('gives %s files their icon and colour', (_, extensions, icon) => {
    for (const extension of extensions) {
      expect(fileIcon(`Lease.${extension}`)).toEqual(icon)
      expect(fileIcon(`LEASE.${extension.toUpperCase()}`)).toEqual(icon)
    }
  })

  it('gives any other file, a drawing included, the plain document in no kind\'s colour', () => {
    for (const name of ['Site Plan.odg', 'notes.txt', 'archive.zip', 'README', 'docx', 'Lease.docx.bak']) {
      expect(fileIcon(name)).toEqual({ name: 'document' })
    }
  })

  it('goes by the extension alone, not a name that only contains one', () => {
    expect(fileIcon('pdf notes.txt')).toEqual({ name: 'document' })
    expect(fileIcon('Budget.xls.pdf')).toEqual({ name: 'filePdf', kind: 'pdf' })
  })
})

describe('the other kinds', () => {
  it('draws matters, clients, folders and search folders in their colours', () => {
    expect(matterIcon).toEqual({ name: 'briefcase', kind: 'matter' })
    expect(clientIcon).toEqual({ name: 'building', kind: 'client' })
    expect(folderIcon()).toEqual({ name: 'folder', kind: 'folder' })
    expect(folderIcon('folder')).toEqual({ name: 'folder', kind: 'folder' })
    expect(folderIcon('searchFolder')).toEqual({ name: 'folderSearch', kind: 'folder' })
    // A matter's own folder, as a favourite may be, is the matter
    expect(folderIcon('matter')).toEqual(matterIcon)
  })

  it('gives each scope, and so each Recent entry, its kind\'s icon; Documents and All take in every kind and have none', () => {
    expect(Object.keys(scopeIcons).sort()).toEqual([...searchScopes].sort())
    expect(scopeIcons).toEqual({
      documents: { name: 'document' },
      emails: { name: 'email', kind: 'email' },
      all: { name: 'search' },
      folders: { name: 'folder', kind: 'folder' },
      matters: { name: 'briefcase', kind: 'matter' },
      clients: { name: 'building', kind: 'client' },
    })
    for (const kind of recentKinds) expect(scopeIcons).toHaveProperty([kind])
  })
})
