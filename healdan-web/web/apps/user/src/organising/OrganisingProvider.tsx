// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useNavigate, useParams } from '@tanstack/react-router'
import { createContext, useContext, useState, type DragEvent, type ReactNode } from 'react'
import type { components } from '@healdan/api'
import { plural, t, toast, toastWithAction } from '@healdan/ui'
import { eachItem, nameOf, type ItemDone, type ItemRefusal, type Movable, type OneItem, type OrganisingItems, type RefusalLine } from '../actions/batch'
import {
  anyMovable,
  deleteFolder,
  folderContents,
  folderDeleteRefusal,
  folderMoveRefusal,
  FolderNotDeletable,
  moveFolder,
  whyNotDelete,
  whyNotMove,
  type MovingFolder,
} from '../actions/folders'
import { moveDocument, planMove, type MovePlan } from '../actions/move'
import { invalidateDocumentLists } from '../actions/refresh'
import { removeDocument } from '../actions/remove'
import { carriesRows, ROWS_TYPE, type Place } from '../filing/dropTargets'
import { refusalText } from '../filing/refusal'
import { idFrom, keys, useMe } from '../queries'
import { useSelection } from '../selection'
import { useApi } from '../services'
import { ConfirmDelete } from './ConfirmDelete'
import { CrossMatter } from './CrossMatter'
import { MovePicker } from './MovePicker'
import { NewFolderDialog } from './NewFolder'
import { Refusals, type RefusalsProps } from './Refusals'
import { readRows } from './rows'
import { undoAction } from './undo'

export type { OrganisingItems } from '../actions/batch'

type S = components['schemas']

/** Where a rename's field is: a list's row, the My Matters tree, or the open folder's heading */
export type RenamePlace = 'row' | 'tree' | 'heading'

/** What is being renamed: its row key (`d10388` for a document, `f45` for a folder) and where its field is */
export interface Renaming {
  key: string
  place: RenamePlace
}

export interface Organising {
  /** What is a field now, and where */
  renaming: Renaming | undefined
  startRename: (key: string, place?: RenamePlace) => void
  /**
   * Ends the rename (saved or not) and gives the focus back where it began, unless `focusBack` is false (the person left the
   * field). A save that ends after another rename began leaves that one open, with the focus where it is.
   */
  stopRename: (focusBack?: boolean) => void
  /** Asks first, then moves the folders and documents to the Trash, saying so with Undo */
  askDelete: (items: OrganisingItems) => void
  /** Opens the picker, then moves the folders and documents where it's told, asking first for a folder leaving its matter */
  askMove: (items: OrganisingItems) => void
  askNewFolder: (parent: Place) => void
  /**
   * Rows dropped on a folder of the tree move there, folders and documents. A drop that doesn't carry them is left alone (the
   * filing's), and what can't go there (documents into a place that holds none, a folder into its own tree) says so
   * before any call is made.
   */
  dropRows: (event: DragEvent, place: Place) => void
}

const Context = createContext<Organising | null>(null)

export function useOrganising(): Organising {
  const organising = useContext(Context)
  if (!organising) throw new Error('useOrganising needs an OrganisingProvider')
  return organising
}

/** Where the focus goes back to once a rename is over: the row, the tree's folder, or the heading's ⋯ */
function renameOrigin({ key, place }: Renaming): HTMLElement | null {
  const found = globalThis.document
  if (place === 'row') return found.querySelector<HTMLElement>(`[role="grid"] [data-row-key="${key}"]`)
  if (place === 'tree') return found.querySelector<HTMLElement>(`[role="treegrid"] [data-tree-key="${key.slice(1)}"]`)
  return found.querySelector<HTMLElement>(`[data-rename-menu="${key}"] button`)
}

/** The rows' keys of the items: `d10388` for a document, `f45` for a folder */
function rowKeys(items: OrganisingItems): string[] {
  return [...items.folders.map((folder) => `f${folder.id}`), ...items.documents.map((document) => `d${document.docNumber}`)]
}

function listedKeys(): string[] {
  return Array.from(globalThis.document.querySelectorAll('[role="grid"] [data-row-key]'), (row) => row.getAttribute('data-row-key') ?? '')
}

/**
 * Where the focus goes once the rows are gone: the key of the row that follows them in the list drawn now, else the one
 * before them, else none. Undefined when none of them is a row of the list (a folder of the tree, or the open one).
 */
function rowAfter(gone: readonly string[]): { key?: string } | undefined {
  const leaving = new Set(gone)
  const keys = listedKeys()
  const last = keys.findLastIndex((key) => leaving.has(key))
  if (last < 0) return undefined
  return { key: keys.slice(last + 1).find((key) => !leaving.has(key)) ?? keys.slice(0, last).findLast((key) => !leaving.has(key)) }
}

/** Focuses a row once it has been drawn again, or the list when there is no such row (as the dialog that had the focus gives it back first) */
function focusLater(key: string | undefined) {
  let tries = 0
  const attempt = () => {
    const target = (key ? globalThis.document.querySelector<HTMLElement>(`[data-row-key="${key}"]`) : null) ?? (key ? null : globalThis.document.querySelector<HTMLElement>('[role="grid"]'))
    if (target) target.focus()
    else if (++tries < 20) setTimeout(attempt, 50)
  }
  setTimeout(attempt, 0)
}

/**
 * Focuses the list once it's drawn, if the focus was lost meanwhile: the open folder's ⋯, which asked, went with the
 * folder, and its parent's list is where the person is now. A focus put elsewhere (the tree, from which a folder above
 * the open one was deleted) stays.
 */
function focusListIfLost() {
  let tries = 0
  const attempt = () => {
    const now = globalThis.document.activeElement
    if (now && now !== globalThis.document.body) return
    const list = globalThis.document.querySelector<HTMLElement>('[role="grid"]')
    if (list) list.focus()
    else if (++tries < 20) setTimeout(attempt, 50)
  }
  setTimeout(attempt, 0)
}

/** The key a refusal's line has: an item's own, so a document and a folder with the same number never share one */
function lineKey(item: OneItem): string {
  return 'document' in item ? `d${item.document.docNumber}` : `f${item.folder.id}`
}

/**
 * What a move says it did, where to (folders and Trash spec 7.5): documents alone as before; one folder by the name the
 * server answered, which CE changes on its way in when a folder the person can't see has its name ("Letters(1)"); else
 * how many items.
 */
function movedText(done: readonly ItemDone<{ name: string }>[], where: string): string {
  const [one] = done
  if (done.every((item) => 'document' in item)) return plural('moved', done.length, { folder: where })
  if (done.length === 1 && one && 'folder' in one) return t('move.doneFolder', { name: one.answer.name, folder: where })
  return plural('move.doneMany', done.length, { folder: where })
}

/**
 * Renaming documents and folders, moving and deleting folders and documents, and making folders (spec 6.7; folders and
 * Trash spec 7.4, 7.5): what is being renamed, the dialogs that ask and the one that says what was refused. Each item is
 * its own call, so a refusal leaves the others done. A delete says so with Undo, which restores what it took. A folder
 * leaving its matter is asked about before it moves.
 */
export function OrganisingProvider({ children }: { children: ReactNode }) {
  const api = useApi()
  const queryClient = useQueryClient()
  const me = useMe()
  const navigate = useNavigate()
  const { id: openId } = useParams({ strict: false })
  const { selected, select } = useSelection()
  const [renaming, setRenaming] = useState<Renaming | undefined>()
  const [deleting, setDeleting] = useState<OrganisingItems | undefined>()
  const [moving, setMoving] = useState<OrganisingItems | undefined>()
  const [parent, setParent] = useState<Place | undefined>()
  const [refused, setRefused] = useState<Pick<RefusalsProps, 'title' | 'lines'> | undefined>()
  // The question a folder leaving its matter asks before it moves: the folder, the matter it goes into, and its answer
  const [crossing, setCrossing] = useState<{ folder: MovingFolder; matter: string; answer: (move: boolean) => void } | undefined>()

  // The one folder the question is about says what it holds that the person can read; asked afresh each time
  const asked = deleting && deleting.documents.length === 0 && deleting.folders.length === 1 ? deleting.folders[0]?.id : undefined
  const contents = useQuery({
    queryKey: keys.contents(asked ?? 0),
    queryFn: () => folderContents(api, asked ?? 0),
    enabled: asked !== undefined,
    staleTime: 0,
    gcTime: 0,
  })

  // What the folder leaving its matter holds that the person can read, for its question's line; asked afresh each time
  const crossingContents = useQuery({
    queryKey: keys.contents(crossing?.folder.id ?? 0),
    queryFn: () => folderContents(api, crossing?.folder.id ?? 0),
    enabled: crossing !== undefined,
    staleTime: 0,
    gcTime: 0,
  })

  /** Asks whether the folder may leave its matter for the place's, and answers once: Move, or Cancel (or Esc) */
  function askCrossing(folder: MovingFolder, place: Place): Promise<boolean> {
    return new Promise((resolve) => {
      let answered = false
      setCrossing({
        folder,
        matter: place.matter ?? place.name ?? '',
        answer: (yes) => {
          if (answered) return
          answered = true
          setCrossing(undefined)
          resolve(yes)
        },
      })
    })
  }

  /**
   * Each refusal of a move as a line of Refusals, in the order the list shows the items (folders first): what the plan
   * refused before any call, with its reason, and what the server refused, in the catalogue's words
   */
  function moveLines(items: OrganisingItems, early: MovePlan['refused'], failed: readonly ItemRefusal[]): RefusalLine[] {
    const lines = new Map<string, RefusalLine>()
    for (const one of early) lines.set(lineKey(one), { key: lineKey(one), name: nameOf(one), reason: t(one.reason) })
    for (const one of failed) {
      const said = 'document' in one ? { reason: refusalText(one.error, me.data?.id) } : folderMoveRefusal(one.error, me.data?.id)
      lines.set(lineKey(one), { key: lineKey(one), name: nameOf(one), ...said })
    }
    return [...items.folders.map((folder) => `f${folder.id}`), ...items.documents.map((document) => `d${document.docNumber}`)].flatMap((key) => lines.get(key) ?? [])
  }

  /**
   * Nothing to move: what is in the place already says so, and why the rest can't go is said without a dialog, as the
   * Delete key does: one item, or documents a place holds none of, by the reason alone, and several by the first, named
   */
  function nothingMoved(refused: MovePlan['refused'], count: number) {
    const [first] = refused
    if (!first) return toast(t('move.sameFolder'))
    if (count === 1 || refused.every((one) => 'document' in one)) toast(t(first.reason))
    else toast(t('organising.refused', { name: nameOf(first), reason: t(first.reason) }))
  }

  /**
   * Moves the items into the place, each with its own call (folders and Trash spec 7.5). What can't go there makes no call
   * and is said in Refusals while the rest is moved; each folder leaving its matter is asked about first, in turn, and
   * one the person keeps is left where it is; the documents go before the folders, so a document moved into a folder that
   * is moving too goes where the person saw it go. `dragged`: a drop moved them, which leaves the focus where the person
   * put it.
   */
  async function move(items: OrganisingItems, place: Place, dragged = false) {
    const plan = planMove(items, place, me.data?.admin)
    if (plan.documents.length + plan.folders.length === 0) return nothingMoved(plan.refused, items.documents.length + items.folders.length)
    const folders: MovingFolder[] = []
    for (const folder of plan.folders) if (folder.matterId === place.matterId || (await askCrossing(folder, place))) folders.push(folder)
    const title = items.folders.length > 0 ? t('move.refusedItemsTitle') : t('move.refusedTitle')
    if (plan.documents.length + folders.length === 0) {
      if (plan.refused.length > 0) setRefused({ title, lines: moveLines(items, plan.refused, []) })
      return
    }
    // Where the focus goes once the rows are gone, as after a delete: the picker that had it is gone, and the page would have it
    const next = rowAfter([...folders.map((folder) => `f${folder.id}`), ...plan.documents.map((one) => `d${one.docNumber}`)])
    const calls = {
      document: (document: Movable) => moveDocument(api, document.docNumber, place.id),
      folder: (folder: MovingFolder) => moveFolder(api, folder.id, place.id),
    }
    const documents = await eachItem<{ name: string }>({ documents: plan.documents, folders: [] }, calls)
    const moved = await eachItem<{ name: string }>({ documents: [], folders }, calls)
    // No doc #s: a moved copy of an email's attachment is still its copy, wherever it went (invalidateDocumentLists)
    await invalidateDocumentLists(queryClient)
    const done = [...documents.done, ...moved.done]
    if (done.length > 0) toast(movedText(done, place.name || t('move.thisFolder')))
    const failed = [...documents.refused, ...moved.refused]
    if (plan.refused.length + failed.length > 0) setRefused({ title, lines: moveLines(items, plan.refused, failed) })
    // Nor does it move for an item that was no row here (the tree's folder, the open one), where the picker gives it back
    if (done.length > 0 && next && !dragged) focusLater(next.key)
  }

  /** Why each refused item wasn't deleted: a folder's line names its readable check-outs under it */
  function deleteRefusals(refusals: readonly (OneItem & { error: unknown })[]): RefusalLine[] {
    return refusals.map((refusal) => {
      const key = lineKey(refusal)
      const name = nameOf(refusal)
      if ('document' in refusal) return { key, name, reason: refusalText(refusal.error, me.data?.id) }
      return { key, name, ...folderDeleteRefusal(refusal.error, me.data?.id) }
    })
  }

  /** Asks again for what the change shows in: documents that went or came back are named, for an email's copies */
  function refresh(documents: readonly number[]) {
    return invalidateDocumentLists(queryClient, documents)
  }

  async function remove(items: OrganisingItems) {
    const next = rowAfter(rowKeys(items))
    const outcome = await eachItem<S['TrashItem']>(items, {
      document: (document) => removeDocument(api, document.docNumber),
      // One of several that says it can't be deleted makes no call: Refusals says why, and the rest is deleted
      folder: (folder) =>
        folder.canDelete ? deleteFolder(api, folder.id) : Promise.reject(new FolderNotDeletable(whyNotDelete(folder, me.data?.admin) ?? 'delete.noPermission')),
    })
    const done = outcome.done.flatMap((one) => ('document' in one ? [one.document.docNumber] : []))
    // What was deleted can't stay selected: its details would answer 404
    if (selected !== undefined && done.includes(selected)) select(undefined)
    // The open folder, deleted or in a deleted folder, would answer 404: its deleted folder's parent opens in its place
    const open = idFrom(openId)
    const path = open === undefined ? undefined : queryClient.getQueryData<S['FolderView']>(keys.folder(open))?.path
    const gone = outcome.done.flatMap((one) => ('folder' in one && one.folder.parentId > 0 && (one.folder.id === open || path?.some((step) => step.id === one.folder.id)) ? [one.folder] : []))
    const [leaving] = gone
    if (leaving) await navigate({ to: '/folders/$id', params: { id: String(leaving.parentId) } })
    await refresh(done)

    const [first] = outcome.done
    if (first) {
      const items = outcome.done.map((one) => one.answer)
      const message = outcome.done.length === 1 ? t('delete.done', { name: nameOf(first) }) : plural('delete.doneMany', outcome.done.length)
      toastWithAction(
        message,
        undoAction(api, items, {
          refresh: (restored) => refresh(restored.flatMap((one) => (one.item.docNumber === null ? [] : [one.item.docNumber]))),
          refuse: setRefused,
          meId: me.data?.id,
        }),
      )
    }
    if (outcome.refused.length > 0) setRefused({ title: t('delete.refusedTitle'), lines: deleteRefusals(outcome.refused) })
    // Where the focus goes once the rows are gone: the next row, or the one before, or the list. With none gone, it stays
    // where it was, as the dialog that says why gives it back there; nor does it move for a folder that was no row here
    if (outcome.done.length > 0 && next && !leaving) focusLater(next.key)
    if (leaving) focusListIfLost()
  }

  const value: Organising = {
    renaming,
    startRename: (key, place = 'row') => setRenaming({ key, place }),
    // Only a field that is drawn calls this: one that has gone (another rename began while it saved) never does (useNameField)
    stopRename: (focusBack = true) => {
      const done = renaming
      setRenaming(undefined)
      // The field is gone once this has rendered; where the rename began is where the focus belongs, unless the person put
      // it elsewhere
      if (focusBack && done) requestAnimationFrame(() => renameOrigin(done)?.focus())
    },
    askDelete: (items) => {
      if (items.documents.length + items.folders.length > 0) setDeleting(items)
    },
    askMove: (items) => {
      if (items.documents.length + items.folders.length === 0) return
      // With nothing that can be moved, the folder says why, as the Delete key does, and nothing is asked: named, of several.
      // Several with something movable are asked about, and those that can't be moved say why in Refusals
      if (!anyMovable(items)) {
        const [blocked] = items.folders
        const why = blocked && whyNotMove(blocked, me.data?.admin)
        if (blocked && why) toast(items.folders.length > 1 ? t('organising.refused', { name: blocked.name, reason: t(why) }) : t(why))
        return
      }
      setMoving(items)
    },
    askNewFolder: setParent,
    dropRows: (event, place) => {
      if (!carriesRows(Array.from(event.dataTransfer.types))) return
      event.preventDefault()
      // Read now: the browser empties the data transfer when the event ends
      const items = readRows(event.dataTransfer.getData(ROWS_TYPE))
      if (items.documents.length + items.folders.length > 0) void move(items, place, true)
    },
  }

  return (
    <Context.Provider value={value}>
      {children}
      {deleting && (
        <ConfirmDelete
          items={deleting}
          contents={contents.data}
          onCancel={() => setDeleting(undefined)}
          onConfirm={() => {
            const items = deleting
            setDeleting(undefined)
            void remove(items)
          }}
        />
      )}
      {moving && (
        <MovePicker
          items={moving}
          onCancel={() => setMoving(undefined)}
          onChoose={(place) => {
            const items = moving
            setMoving(undefined)
            void move(items, place)
          }}
        />
      )}
      {crossing && (
        <CrossMatter
          // A dialog of its own for each folder, as one question follows another
          key={crossing.folder.id}
          name={crossing.folder.name}
          matter={crossing.matter}
          contents={crossingContents.data}
          onConfirm={() => crossing.answer(true)}
          onCancel={() => crossing.answer(false)}
        />
      )}
      {parent && <NewFolderDialog parent={parent} onCancel={() => setParent(undefined)} />}
      {refused && <Refusals title={refused.title} lines={refused.lines} onClose={() => setRefused(undefined)} />}
    </Context.Provider>
  )
}
