// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { ApiError, apiPath, type Api, type components } from '@healdan/api'
import { t, type StringKey } from '@healdan/ui'
import { refusalText } from '../filing/refusal'
import { messageFor } from '../frame/errors'
import { holdSentence, holdState } from './checkout'
import { isTopLevel } from './rename'

type S = components['schemas']

/** Moves the folder, and everything live in it, to the Trash, and answers the Trash item the delete made */
export function deleteFolder(api: Api, id: number): Promise<S['TrashItem']> {
  return api.send('DELETE', apiPath`/v1/folders/${id}`)
}

export function moveFolder(api: Api, id: number, folderId: number): Promise<S['FolderView']> {
  return api.send('POST', apiPath`/v1/folders/${id}/move`, { folderId })
}

/** What the folder holds that the person can read: its live subfolders, at every level, and its documents */
export function folderContents(api: Api, id: number): Promise<S['TrashContents']> {
  return api.get(apiPath`/v1/folders/${id}/contents`)
}

/** A folder being moved or deleted, from a row, the tree, the heading or a drag */
export interface MovingFolder {
  id: number
  name: string
  /** The folder it's in; 0 for one whose path doesn't say (a Store, which is never moved or deleted) */
  parentId: number
  matterId: number | null
  canMove: boolean
  canDelete: boolean
  /** Its kind, and whether it's directly in a matter's workspace, which say why it can't be moved or deleted */
  kind?: S['FolderView']['kind']
  topLevel?: boolean
}

export function movingFolder(view: S['FolderView']): MovingFolder {
  return {
    id: view.id,
    name: view.name,
    parentId: view.path.at(-2)?.id ?? 0,
    matterId: view.matter?.id ?? null,
    canMove: view.canMove,
    canDelete: view.canDelete,
    kind: view.kind,
    topLevel: isTopLevel(view),
  }
}

/** A folder as a list, the tree or the heading has it, or as it's being moved or deleted */
type Described = Pick<S['FolderView'], 'kind' | 'path'> | Pick<MovingFolder, 'kind' | 'topLevel'>

function topLevelOf(folder: Described): boolean {
  return 'path' in folder ? isTopLevel(folder) : folder.topLevel === true
}

/**
 * Why the person can't delete the folder, for the Delete key to say; undefined when they can. The server decides, in
 * `canDelete`; this says which of its reasons it is, from what the app already knows, as whyNotRename does.
 */
export function whyNotDelete(folder: Described & { canDelete: boolean }, admin: boolean | undefined): StringKey | undefined {
  if (folder.canDelete) return undefined
  if (folder.kind === 'matter') return 'delete.matterFolder'
  if (folder.kind === 'store') return 'problems.not-deletable'
  if (topLevelOf(folder) && !admin) return 'delete.adminsOnly'
  return 'delete.noPermission'
}

/** Why the person can't move the folder; undefined when they can (`canMove`), as whyNotDelete */
export function whyNotMove(folder: Described & { canMove: boolean }, admin: boolean | undefined): StringKey | undefined {
  if (folder.canMove) return undefined
  if (folder.kind === 'matter') return 'move.matterFolder'
  if (folder.kind === 'store') return 'problems.not-movable'
  if (topLevelOf(folder) && !admin) return 'move.adminsOnlyFolder'
  return 'move.noPermission'
}

/**
 * Why only an administrator may make the move: a folder at a matter's top level is moved by them alone, and any other
 * folder goes into a matter's top level only by them
 */
export function adminsOnlyMove(folder: Pick<MovingFolder, 'topLevel'>): StringKey {
  return folder.topLevel === true ? 'move.adminsOnlyFolder' : 'move.adminsOnlyInto'
}

/**
 * Whether a selection holds anything that can be moved: a document, or a folder whose `canMove` says so. A folder of
 * several that can't is said in Refusals while the rest is moved (folders and Trash spec 7.5), as anyDeletable.
 */
export function anyMovable(items: { documents: readonly unknown[]; folders: readonly Pick<MovingFolder, 'canMove'>[] }): boolean {
  return items.documents.length > 0 || items.folders.some((folder) => folder.canMove)
}

/**
 * A folder of several being deleted together that says it can't be: it makes no call, and is said in Refusals with its
 * reason while the rest is deleted (folders and Trash spec 7.5)
 */
export class FolderNotDeletable extends Error {
  constructor(readonly reason: StringKey) {
    super(reason)
    this.name = 'FolderNotDeletable'
  }
}

/** Whether a selection holds anything that can be deleted: a document, or a folder whose `canDelete` says so */
export function anyDeletable(items: { documents: readonly unknown[]; folders: readonly Pick<MovingFolder, 'canDelete'>[] }): boolean {
  return items.documents.length > 0 || items.folders.some((folder) => folder.canDelete)
}

/** What a refused folder delete or move says: why, the checked-out documents it names, and what can be done about them */
export interface FolderRefusal {
  reason: string
  /** Each checked-out document in it that the person can read, with who has it and since when */
  details?: string[]
  after?: string
}

/**
 * Why a folder wasn't deleted, from the catalogue (spec 4.2, 7.5): what it holds that the person may not delete is named
 * by nothing, whether they can see it or not, and of its check-outs only those the problem's `holds` names, the documents
 * the person can read. "And items you can't see" is never said.
 */
export function folderDeleteRefusal(error: unknown, meId: number | undefined): FolderRefusal {
  if (error instanceof FolderNotDeletable) return { reason: t(error.reason) }
  if (error instanceof ApiError) {
    if (error.code === 'items-not-allowed') return { reason: t('delete.notAllowed') }
    if (error.code === 'admins-only') return { reason: t('delete.adminsOnly') }
    if (error.code === 'forbidden') return { reason: t('delete.noPermission') }
    if (error.code === 'checked-out') return { reason: t('delete.checkedOut'), details: heldNames(error, meId), after: t('delete.checkedOutAfter') }
  }
  return { reason: messageFor(error) }
}

/**
 * Each checked-out document a `checked-out` refusal names, with who has it and since when: only those in the problem's
 * `holds`, the documents the person can read
 */
function heldNames(error: ApiError, meId: number | undefined): string[] {
  const holds = (error.body as { holds?: S['HeldDocument'][] } | null)?.holds
  return (Array.isArray(holds) ? holds : []).map((held) => {
    const who = holdSentence(holdState(held.hold, meId))
    return who ? t('organising.refused', { name: held.name, reason: who }) : held.name
  })
}

/**
 * Why a folder wasn't moved, from the catalogue (spec 4.3, 7.5), as folderDeleteRefusal: what it holds that the person
 * may not move is named by nothing, and of its check-outs only those the problem's `holds` names. The folder says which
 * cause the server's answer is (`FolderChanges.refuseMove`): `admins-only` is its own top level, or else the matter's top
 * level it was going into; `forbidden` is no Move on it when its `canMove` says so, or else no Add where it was going. A
 * name already taken, and an email already in the matter it went to, have the words a document's move has.
 */
export function folderMoveRefusal(error: unknown, folder: Pick<MovingFolder, 'canMove' | 'topLevel'>, meId: number | undefined): FolderRefusal {
  if (error instanceof ApiError) {
    if (error.code === 'items-not-allowed') return { reason: t('move.notAllowed') }
    if (error.code === 'admins-only') return { reason: t(adminsOnlyMove(folder)) }
    if (error.code === 'forbidden') return { reason: t(folder.canMove ? 'move.noAddThere' : 'move.noPermission') }
    if (error.code === 'checked-out') return { reason: t('delete.checkedOut'), details: heldNames(error, meId), after: t('move.checkedOutAfter') }
  }
  return { reason: refusalText(error, meId) }
}
