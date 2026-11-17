// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useNavigate, useParams } from '@tanstack/react-router'
import { createContext, useContext, useState, type DragEvent, type ReactNode } from 'react'
import type { components } from '@healdan/api'
import { plural, t, toast, toastWithAction } from '@healdan/ui'
import { eachItem, nameOf, refusalLines, type Movable, type OneItem, type OrganisingItems, type Outcome, type RefusalLine } from '../actions/batch'
import { deleteFolder, folderContents, folderDeleteRefusal } from '../actions/folders'
import { moveDocuments } from '../actions/move'
import { invalidateDocumentLists } from '../actions/refresh'
import { removeDocument } from '../actions/remove'
import { carriesRows, refusalOf, ROWS_TYPE, type Place } from '../filing/dropTargets'
import { refusalText } from '../filing/refusal'
import { idFrom, keys, useMe } from '../queries'
import { useSelection } from '../selection'
import { useApi } from '../services'
import { ConfirmDelete } from './ConfirmDelete'
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
  /** Opens the picker, then moves the documents where it's told */
  askMove: (items: OrganisingItems) => void
  askNewFolder: (parent: Place) => void
  /**
   * Rows dropped on a folder of the tree move there. A drop that doesn't carry them is left alone (the filing's), and one on a
   * place that holds no documents says so before any call is made.
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
 * Renaming documents and folders, moving documents, deleting folders and documents, and making folders (spec 6.7;
 * folders and Trash spec 7.4, 7.5): what is being renamed, the dialogs that ask and the one that says what was refused.
 * Each item is its own call, so a refusal leaves the others done. A delete says so with Undo, which restores what it took.
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

  // The one folder the question is about says what it holds that the person can read; asked afresh each time
  const asked = deleting && deleting.documents.length === 0 && deleting.folders.length === 1 ? deleting.folders[0]?.id : undefined
  const contents = useQuery({
    queryKey: keys.contents(asked ?? 0),
    queryFn: () => folderContents(api, asked ?? 0),
    enabled: asked !== undefined,
    staleTime: 0,
    gcTime: 0,
  })

  /** What a change to several documents did, said: one line, and a dialog for what was refused */
  function report(outcome: Outcome, done: string, title: string) {
    if (outcome.done.length > 0) toast(done)
    if (outcome.refused.length > 0) setRefused({ title, lines: refusalLines(outcome.refused, me.data?.id) })
  }

  /** `dragged`: a drop moved them, which leaves the focus where the person put it */
  async function move(documents: readonly Movable[], folder: { id: number; name: string }, dragged = false) {
    // What is in the folder already isn't moved, and isn't counted
    const todo = documents.filter((one) => one.folder?.id !== folder.id)
    if (todo.length === 0) return toast(t('move.sameFolder'))
    // Where the focus goes once the rows are gone, as after a delete: the picker that had it is gone, and the page would have it
    const next = rowAfter(todo.map((one) => `d${one.docNumber}`))
    const outcome = await moveDocuments(api, todo, folder.id)
    // No doc #s: a moved copy of an email's attachment is still its copy, wherever it went (invalidateDocumentLists)
    await invalidateDocumentLists(queryClient)
    report(outcome, plural('moved', outcome.done.length, { folder: folder.name || t('move.thisFolder') }), t('move.refusedTitle'))
    if (outcome.done.length > 0 && !dragged) focusLater(next?.key)
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
      folder: (folder) => deleteFolder(api, folder.id),
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
    askMove: setMoving,
    askNewFolder: setParent,
    dropRows: (event, place) => {
      if (!carriesRows(Array.from(event.dataTransfer.types))) return
      event.preventDefault()
      // Read now: the browser empties the data transfer when the event ends
      const documents = readRows(event.dataTransfer.getData(ROWS_TYPE))
      const refusal = refusalOf(place)
      if (refusal) toast(t(refusal))
      else if (documents.length > 0) void move(documents, { id: place.id, name: place.name ?? '' }, true)
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
          documents={moving.documents}
          onCancel={() => setMoving(undefined)}
          onChoose={(folder) => {
            const { documents } = moving
            setMoving(undefined)
            void move(documents, folder)
          }}
        />
      )}
      {parent && <NewFolderDialog parent={parent} onCancel={() => setParent(undefined)} />}
      {refused && <Refusals title={refused.title} lines={refused.lines} onClose={() => setRefused(undefined)} />}
    </Context.Provider>
  )
}
