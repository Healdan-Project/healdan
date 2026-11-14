// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { components } from '@healdan/api'
import type { IconKind, IconName } from '@healdan/ui'

type S = components['schemas']

/** An icon, and the kind of item it stands for, whose colour it draws in: `<Icon {...fileIcon(name)} />`. */
export interface KindIcon {
  name: IconName
  kind?: IconKind
}

export const matterIcon: KindIcon = { name: 'briefcase', kind: 'matter' }
export const clientIcon: KindIcon = { name: 'building', kind: 'client' }
export const emailIcon: KindIcon = { name: 'email', kind: 'email' }

/** A plain document, in the colour of what it sits in: any other kind of file, such as a drawing. */
export const documentIcon: KindIcon = { name: 'document' }

// The extensions are the server's kinds (FileKinds, the one list the File Type filter uses too; kindIcons.test.ts
// compares them), and each takes its kind's colour, with LibreOffice's formats in their Office counterparts' colours
// (owner, 2026-11-14). A format with a type icon of its own in Tabler gets it; the others the plain one of their kind. A
// CSV is Other to the server, so it has its own icon in no kind's colour.
const groups: [string[], KindIcon][] = [
  [['pdf'], { name: 'filePdf', kind: 'pdf' }],
  [['docx', 'docm', 'dotx', 'dotm'], { name: 'fileDocx', kind: 'wordProcessing' }],
  [['doc', 'dot'], { name: 'fileDoc', kind: 'wordProcessing' }],
  [['rtf', 'odt', 'ott', 'fodt'], { name: 'document', kind: 'wordProcessing' }],
  [['xlsx', 'xlsm', 'xlsb', 'xltx', 'xltm', 'xls', 'xlt'], { name: 'fileXls', kind: 'spreadsheet' }],
  [['ods', 'ots', 'fods'], { name: 'spreadsheet', kind: 'spreadsheet' }],
  [['pptx', 'pptm', 'ppsx', 'ppsm', 'potx', 'potm', 'ppt', 'pps', 'pot'], { name: 'filePpt', kind: 'presentation' }],
  [['odp', 'otp', 'fodp'], { name: 'presentation', kind: 'presentation' }],
  [['eml', 'msg'], emailIcon],
  [['jpg', 'jpeg', 'png', 'gif', 'bmp', 'tif', 'tiff', 'webp', 'heic', 'heif', 'svg'], { name: 'picture', kind: 'picture' }],
  [['csv'], { name: 'fileCsv' }],
]

/** Each extension's icon, in lower case */
export const fileIcons: ReadonlyMap<string, KindIcon> = new Map(groups.flatMap(([extensions, icon]) => extensions.map((extension) => [extension, icon] as const)))

/** The icon of a file's kind, from its name's extension; a plain document when the kind is unknown. */
export function fileIcon(name: string): KindIcon {
  const dot = name.lastIndexOf('.')
  return (dot < 0 ? undefined : fileIcons.get(name.slice(dot + 1).toLowerCase())) ?? documentIcon
}

/** A folder's, from its kind: a search folder has its own, both in the folders' colour, and a matter's own folder is the matter. */
export function folderIcon(kind: S['FolderView']['kind'] = 'folder'): KindIcon {
  if (kind === 'matter') return matterIcon
  return { name: kind === 'searchFolder' ? 'folderSearch' : 'folder', kind: 'folder' }
}

/** A list row's: a matter's, a folder's by its kind, or a file's by its name */
export function itemIcon(item: S['MatterView'] | S['FolderView'] | S['DocumentView']): KindIcon {
  if (item.type === 'matter') return matterIcon
  if (item.type === 'folder') return folderIcon(item.kind)
  return fileIcon(item.name)
}
