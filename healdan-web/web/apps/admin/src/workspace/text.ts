// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { components } from '@healdan/api'
import { plural, t } from '../strings'

/** The longest folder name the server keeps (`FolderName`) */
export const FOLDER_NAME_MAX = 255

/**
 * What's wrong with a folder's name, a template's or a matter's before the server would say so (spec 7.2), or undefined:
 * a slash or a backslash, more than `max` characters, or a name another of `taken` has, ignoring case. A blank name is no
 * problem to say: the button waits for one.
 */
export function nameProblem(name: string, taken: readonly string[] = [], max = FOLDER_NAME_MAX): string | undefined {
  const trimmed = name.trim()
  if (/[/\\]/.test(trimmed)) return t('admin.folderDialog.invalid')
  if (trimmed.length > max) return t('fields.too-long')
  const lower = trimmed.toLowerCase()
  if (trimmed !== '' && taken.some((one) => one.toLowerCase() === lower)) return t('rename.taken', { name: trimmed })
  return undefined
}

const sentences = new Intl.ListFormat('en-US', { type: 'conjunction', style: 'long' })

/** Things named in a sentence: "Evidence and Privileged", "A, B, and C", as US English writes them */
export function andList(parts: readonly string[]): string {
  return sentences.format(parts)
}

/** What a folder holds, as "1 folder and 3 documents", naming only what there is; undefined when it holds nothing */
export function holdsWhat(contents: components['schemas']['TrashContents'] | undefined): string | undefined {
  if (contents === undefined) return undefined
  const parts = [contents.folders > 0 && plural('folders', contents.folders), contents.documents > 0 && plural('documents', contents.documents)].filter(
    (part): part is string => typeof part === 'string',
  )
  return parts.length === 0 ? undefined : andList(parts)
}
