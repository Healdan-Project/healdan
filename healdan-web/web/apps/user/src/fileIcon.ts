// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { IconName } from '@healdan/ui'

const kinds: [RegExp, IconName][] = [
  [/\.pdf$/i, 'filePdf'],
  [/\.(docx?|docm|dotx?|rtf)$/i, 'fileWord'],
  [/\.(xlsx?|xlsm|csv)$/i, 'spreadsheet'],
  [/\.(pptx?|pptm)$/i, 'presentation'],
  [/\.(eml|msg)$/i, 'email'],
  [/\.(png|jpe?g|gif|tiff?|bmp|webp|heic)$/i, 'picture'],
]

/** The icon of a file's kind, from its name; a plain document when the kind is unknown. */
export function fileIcon(name: string): IconName {
  return kinds.find(([pattern]) => pattern.test(name))?.[1] ?? 'document'
}
