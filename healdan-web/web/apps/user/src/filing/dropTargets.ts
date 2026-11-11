// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { components } from '@healdan/api'
import type { StringKey } from '@healdan/ui'

type S = components['schemas']

/**
 * The type a drag of the app's own rows carries (Task 20 moves them). Filing leaves a drop that carries it alone, so
 * that drop is never answered with "no files".
 */
export const ROWS_TYPE = 'application/x-healdan-documents'

/** Files from the computer: what a drag out of Finder carries, and a message dragged out of Outlook in Safari */
export function carriesFiles(types: readonly string[]): boolean {
  return types.includes('Files')
}

/**
 * Whether a drag is one the filing takes over, which means cancelling its dragover so the browser drops it here:
 * files, and a link or text, which a drop answers with where to find the file (a browser would otherwise follow
 * the link and leave the page, and its uploads). The app's own rows aren't: Task 20's move takes them.
 */
export function takesDrag(types: readonly string[]): boolean {
  return !carriesRows(types) && (carriesFiles(types) || types.includes('text/uri-list') || types.includes('text/plain'))
}

export function carriesRows(types: readonly string[]): boolean {
  return types.includes(ROWS_TYPE)
}

/** A folder something may be dropped on: the open one, or one in the tree, whose permission isn't known there */
export interface Place {
  id: number
  kind: S['FolderView']['kind']
  canFile?: boolean
  /** What the tray calls it */
  name?: string
}

export function placeOf(folder: S['FolderView']): Place {
  return { id: folder.id, kind: folder.kind, canFile: folder.canFile, name: folder.name }
}

const extensionOf = (name: string) => {
  const dot = name.lastIndexOf('.')
  return dot > 0 && dot < name.length - 1 ? name.slice(dot + 1).toLowerCase() : undefined
}

/** Whether a file could be a version of a document: the server keeps a document's extension, compared in any case */
export function sameExtension(fileName: string, documentName: string): boolean {
  const ext = extensionOf(fileName)
  return ext !== undefined && ext === extensionOf(documentName)
}

/**
 * Why nothing is filed on a place, as the catalogue's key: a matter's top level, a search folder and a Store hold no
 * documents (M1's filing rule), and a folder the person may not write to takes none. The server checks all of it again.
 */
export function refusalOf(place: Place): StringKey | undefined {
  if (place.kind !== 'folder') return 'filing.chooseFolder'
  if (place.canFile === false) return 'filing.noWrite'
  return undefined
}
