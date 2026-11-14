// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { describe, expect, it } from 'vitest'
import type { IconKind } from '@healdan/ui'
import constantsJava from '../../../../../healdan-core/src/main/java/com/logicaldoc/healdan/HealdanConstants.java?raw'
import fileKindsJava from '../../../../../healdan-core/src/main/java/com/logicaldoc/healdan/listing/FileKinds.java?raw'
import { clientIcon, fileIcon, fileIcons, folderIcon, itemIcon, matterIcon } from './kindIcons'
import { recentKinds, scopeIcons, searchScopes } from './search/scopes'
import { document, folder, matter } from './test/fakeApi'

// The extension decides the icon and the colour; LibreOffice's formats take their Office counterparts' (owner, 2026-11-14)
const groups: [string, string[], ReturnType<typeof fileIcon>][] = [
  ['Word', ['docx', 'docm', 'dotx', 'dotm'], { name: 'fileDocx', kind: 'wordProcessing' }],
  ['older Word', ['doc', 'dot'], { name: 'fileDoc', kind: 'wordProcessing' }],
  ['RTF and LibreOffice Writer', ['rtf', 'odt', 'ott', 'fodt'], { name: 'document', kind: 'wordProcessing' }],
  ['Excel', ['xlsx', 'xlsm', 'xlsb', 'xltx', 'xltm', 'xls', 'xlt'], { name: 'fileXls', kind: 'spreadsheet' }],
  ['LibreOffice Calc', ['ods', 'ots', 'fods'], { name: 'spreadsheet', kind: 'spreadsheet' }],
  ['PowerPoint', ['pptx', 'pptm', 'ppsx', 'ppsm', 'potx', 'potm', 'ppt', 'pps', 'pot'], { name: 'filePpt', kind: 'presentation' }],
  ['LibreOffice Impress', ['odp', 'otp', 'fodp'], { name: 'presentation', kind: 'presentation' }],
  ['PDF', ['pdf'], { name: 'filePdf', kind: 'pdf' }],
  ['email', ['eml', 'msg'], { name: 'email', kind: 'email' }],
  ['picture', ['png', 'jpg', 'jpeg', 'gif', 'tif', 'tiff', 'bmp', 'webp', 'heic', 'heif', 'svg'], { name: 'picture', kind: 'picture' }],
  // Other to the server, so in no kind's colour
  ['CSV', ['csv'], { name: 'fileCsv' }],
]

// The server's kinds of file (FileKinds, which the File Type filter uses), from its source: each kind's extensions
function serverKinds(): Record<string, string[]> {
  const quoted = (list: string) => [...list.matchAll(/"(\w+)"/g)].map((m) => m[1]!)
  const kinds = Object.fromEntries([...fileKindsJava.matchAll(/kinds\.put\((\w+), Set\.of\(([^)]*)\)\)/g)].map((m) => [m[1]!, quoted(m[2]!)]))
  kinds.EMAIL = quoted(/EMAIL_EXTENSIONS = Set\.of\(([^)]*)\)/.exec(constantsJava)![1]!)
  return kinds
}

// The colour each of the server's kinds draws in; LibreOffice's drawings and formulas have none
const colours: Record<string, IconKind> = { WORD: 'wordProcessing', EXCEL: 'spreadsheet', POWERPOINT: 'presentation', PDF: 'pdf', EMAIL: 'email', PICTURE: 'picture' }
const openDocument: Record<string, IconKind | undefined> = {
  odt: 'wordProcessing',
  ott: 'wordProcessing',
  fodt: 'wordProcessing',
  ods: 'spreadsheet',
  ots: 'spreadsheet',
  fods: 'spreadsheet',
  odp: 'presentation',
  otp: 'presentation',
  fodp: 'presentation',
  odg: undefined,
  otg: undefined,
  fodg: undefined,
  odf: undefined,
}

describe('fileIcon', () => {
  it.each(groups)('gives %s files their icon and colour', (_, extensions, icon) => {
    for (const extension of extensions) {
      expect(fileIcon(`Lease.${extension}`)).toEqual(icon)
      expect(fileIcon(`LEASE.${extension.toUpperCase()}`)).toEqual(icon)
    }
  })

  it('gives any other file, a drawing included, the plain document in no kind\'s colour', () => {
    for (const name of ['Site Plan.odg', 'notes.txt', 'archive.zip', 'README', 'docx', 'Lease.docx.bak', 'Lease.constructor']) {
      expect(fileIcon(name)).toEqual({ name: 'document' })
    }
  })

  it('goes by the extension alone, not a name that only contains one', () => {
    expect(fileIcon('pdf notes.txt')).toEqual({ name: 'document' })
    expect(fileIcon('Budget.xls.pdf')).toEqual({ name: 'filePdf', kind: 'pdf' })
  })

  it('colours a file by the server\'s kinds, the File Type filter\'s, and colours nothing they leave out', () => {
    const kinds = serverKinds()
    expect(Object.keys(kinds).sort()).toEqual([...Object.keys(colours), 'OPENDOCUMENT'].sort())
    expect(Object.keys(openDocument).sort()).toEqual([...kinds.OPENDOCUMENT!].sort())
    const expected = new Map(Object.entries(kinds).flatMap(([kind, extensions]) => extensions.map((extension) => [extension, kind === 'OPENDOCUMENT' ? openDocument[extension] : colours[kind]] as const)))
    for (const [extension, kind] of expected) expect([extension, fileIcon(`Lease.${extension}`).kind]).toEqual([extension, kind])
    for (const [extension, icon] of fileIcons) if (icon.kind) expect([extension, expected.get(extension)]).toEqual([extension, icon.kind])
  })
})

describe('the other kinds', () => {
  it('draws matters, clients, folders and search folders in their colours', () => {
    expect(matterIcon).toEqual({ name: 'briefcase', kind: 'matter' })
    expect(clientIcon).toEqual({ name: 'building', kind: 'client' })
    expect(folderIcon()).toEqual({ name: 'folder', kind: 'folder' })
    expect(folderIcon('folder')).toEqual({ name: 'folder', kind: 'folder' })
    expect(folderIcon('store')).toEqual({ name: 'folder', kind: 'folder' })
    expect(folderIcon('searchFolder')).toEqual({ name: 'folderSearch', kind: 'folder' })
    // A matter's own folder, as a favourite or a client's folder's row may be, is the matter
    expect(folderIcon('matter')).toEqual(matterIcon)
  })

  it('gives a list row the icon of what it is', () => {
    expect(itemIcon(matter())).toEqual(matterIcon)
    expect(itemIcon(folder({ kind: 'searchFolder' }))).toEqual({ name: 'folderSearch', kind: 'folder' })
    expect(itemIcon(folder({ kind: 'matter' }))).toEqual(matterIcon)
    expect(itemIcon(document({ name: 'Cap Table.xlsb' }))).toEqual({ name: 'fileXls', kind: 'spreadsheet' })
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
