// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { ApiError, apiPath, type Api, type components } from '@healdan/api'

type S = components['schemas']

/** The Trash's views: the person's own, and for administrators everyone's and what was removed (folders and Trash spec 7.3) */
export const trashViews = ['mine', 'everyone', 'removed'] as const
export type TrashViewName = (typeof trashViews)[number]

/** What the Trash sorts by (list columns spec 5.2): every column of its three views, each by its id */
export const trashSorts = ['name', 'docNumber', 'deletedFrom', 'deleted', 'deletedBy', 'removedBy', 'removed', 'destroyed'] as const
export type TrashSort = (typeof trashSorts)[number]

export function isTrashView(value: unknown): value is TrashViewName {
  return trashViews.some((view) => view === value)
}

export function isTrashSort(value: unknown): value is TrashSort {
  return trashSorts.some((sort) => sort === value)
}

/** Items come 100 at a time, as a folder's rows do (useListing's PAGE_SIZE) */
export const TRASH_PAGE_SIZE = 100

/** A page of the Trash; no sort is the server's own order, newest first (deleted, or removed in Removed) */
export function listTrash(api: Api, query: { view: TrashViewName; sort?: string; order?: string; offset?: number }): Promise<S['TrashPage']> {
  return api.get<S['TrashPage']>('/v1/trash', { view: query.view, sort: query.sort, order: query.order, offset: query.offset ?? 0, limit: TRASH_PAGE_SIZE })
}

/** Restores the item back where it was deleted from, or into the folder given */
export function restoreTrashItem(api: Api, id: number, folderId?: number): Promise<S['RestoredItem']> {
  return api.send('POST', apiPath`/v1/trash/${id}/restore`, folderId === undefined ? undefined : { folderId })
}

/** Moves the item to Removed, where only an administrator can restore it */
export function removeTrashItem(api: Api, id: number): Promise<void> {
  return api.send('DELETE', apiPath`/v1/trash/${id}`)
}

/** Moves everything the person deleted that is still in the Trash to Removed */
export function emptyTrash(api: Api): Promise<void> {
  return api.send('DELETE', '/v1/trash')
}

/** What restoring several items did: each is its own call, so a refusal leaves the others done */
export interface RestoreOutcome {
  done: S['RestoredItem'][]
  /** Their place is gone, or no longer takes them: the person is asked where */
  placeGone: S['TrashItem'][]
  refused: { item: S['TrashItem']; error: unknown }[]
}

/** Restores each, back where it was; one that answers place-gone is returned for the picker */
export async function restoreItems(api: Api, items: readonly S['TrashItem'][]): Promise<RestoreOutcome> {
  const outcome: RestoreOutcome = { done: [], placeGone: [], refused: [] }
  // One after another, so the server never has two at once, as a batch of documents is sent (actions/batch.ts)
  for (const item of items) {
    try {
      outcome.done.push(await restoreTrashItem(api, item.id))
    } catch (error) {
      if (error instanceof ApiError && error.status === 409 && error.code === 'place-gone') outcome.placeGone.push(item)
      else outcome.refused.push({ item, error })
    }
  }
  return outcome
}
