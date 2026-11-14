// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { components } from '@healdan/api'
import type { IconKind, IconName } from '@healdan/ui'

/** An icon, and the kind of item it stands for, whose colour it draws in: `<Icon {...fileIcon(name)} />`. */
export interface KindIcon {
  name: IconName
  kind?: IconKind
}

// The extension decides the icon and the colour, and LibreOffice's formats take their Office counterparts' colours
// (owner, 2026-11-14). A format with a type icon of its own in Tabler gets it; the others the plain one of their kind.
const files: [RegExp, KindIcon][] = [
  [/\.pdf$/i, { name: 'filePdf', kind: 'pdf' }],
  [/\.(docx|docm|dotx|dotm)$/i, { name: 'fileDocx', kind: 'wordProcessing' }],
  [/\.(doc|dot)$/i, { name: 'fileDoc', kind: 'wordProcessing' }],
  [/\.(rtf|odt|ott)$/i, { name: 'document', kind: 'wordProcessing' }],
  [/\.(xlsx|xlsm|xltx|xls)$/i, { name: 'fileXls', kind: 'spreadsheet' }],
  [/\.csv$/i, { name: 'fileCsv', kind: 'spreadsheet' }],
  [/\.(ods|ots)$/i, { name: 'spreadsheet', kind: 'spreadsheet' }],
  [/\.(pptx|pptm|ppsx|potx|ppt)$/i, { name: 'filePpt', kind: 'presentation' }],
  [/\.(odp|otp)$/i, { name: 'presentation', kind: 'presentation' }],
  [/\.(eml|msg)$/i, { name: 'email', kind: 'email' }],
  [/\.(png|jpe?g|gif|tiff?|bmp|webp|heic)$/i, { name: 'picture', kind: 'picture' }],
]

/** A plain document, in the colour of what it sits in: any other kind of file, such as a drawing. */
export const documentIcon: KindIcon = { name: 'document' }

/** The icon of a file's kind, from its name; a plain document when the kind is unknown. */
export function fileIcon(name: string): KindIcon {
  return files.find(([pattern]) => pattern.test(name))?.[1] ?? documentIcon
}

export const matterIcon: KindIcon = { name: 'briefcase', kind: 'matter' }
export const clientIcon: KindIcon = { name: 'building', kind: 'client' }
export const emailIcon: KindIcon = { name: 'email', kind: 'email' }

/** A folder's, from its kind: a search folder has its own, both in the folders' colour, and a matter's own folder is the matter. */
export function folderIcon(kind: components['schemas']['FolderView']['kind'] = 'folder'): KindIcon {
  if (kind === 'matter') return matterIcon
  return { name: kind === 'searchFolder' ? 'folderSearch' : 'folder', kind: 'folder' }
}
