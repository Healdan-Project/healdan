// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { apiPath, type Api, type components } from '@healdan/api'
import type { StringKey } from '@healdan/ui'
import { refusalOf, type Place } from '../filing/dropTargets'
import type { Movable, OneItem, OrganisingItems } from './batch'
import { adminsOnlyMove, whyNotMove, type MovingFolder } from './folders'

type S = components['schemas']

export function moveDocument(api: Api, docNumber: number, folderId: number): Promise<S['DocumentView']> {
  return api.send('POST', apiPath`/v1/documents/${docNumber}/move`, { folderId })
}

/** What a move into a place will do: the documents and folders it moves, and those it refuses before any call, with why */
export interface MovePlan {
  documents: Movable[]
  folders: MovingFolder[]
  refused: (OneItem & { reason: StringKey })[]
}

/**
 * Works out a move before any call is made (folders and Trash spec 7.5). What's in the place already is left out, as moving
 * it there would change nothing. A place that holds no documents refuses them with the filing rule's words (M1). A folder
 * isn't moved into itself or its own tree, nor when it says it can't be (`canMove`), nor into a Store or a search folder,
 * nor by anyone but an administrator into a matter's top level. The server checks all of it again.
 */
export function planMove(items: OrganisingItems, place: Place, admin: boolean | undefined): MovePlan {
  const plan: MovePlan = { documents: [], folders: [], refused: [] }
  for (const folder of items.folders) {
    if (folder.parentId === place.id) continue
    // The place's path holds the place itself. Said as a sentence: the picker's "The folder itself" is a label
    const inside = folder.id === place.id || place.path?.includes(folder.id) === true
    const reason = inside ? 'move.intoItself' : (whyNotMove(folder, admin) ?? folderRule(folder, place, admin))
    if (reason) plan.refused.push({ folder, reason })
    else plan.folders.push(folder)
  }
  const documentRule = refusalOf(place)
  for (const document of items.documents) {
    if (document.folder?.id === place.id) continue
    if (documentRule) plan.refused.push({ document, reason: documentRule })
    else plan.documents.push(document)
  }
  return plan
}

/**
 * Why the folder doesn't go into the place, by M1's placement rules; undefined when it may. A matter's top level is an
 * administrator's: for a top-level folder that's moving it at all, and for any other moving it into the top level.
 */
function folderRule(folder: MovingFolder, place: Place, admin: boolean | undefined): StringKey | undefined {
  if (place.kind === 'store' || place.kind === 'searchFolder') return 'filing.chooseFolder'
  if (place.kind === 'matter' && !admin) return adminsOnlyMove(folder)
  return undefined
}
