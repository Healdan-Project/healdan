// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQueryClient } from '@tanstack/react-query'
import { createContext, useContext, useState, type DragEvent, type ReactNode } from 'react'
import { plural, t, toast } from '@healdan/ui'
import { refusalLines, type Movable, type Outcome } from '../actions/batch'
import { moveDocuments } from '../actions/move'
import { invalidateDocumentLists } from '../actions/refresh'
import { removeDocuments } from '../actions/remove'
import { carriesRows, refusalOf, ROWS_TYPE, type Place } from '../filing/dropTargets'
import { useMe } from '../queries'
import { useSelection } from '../selection'
import { useApi } from '../services'
import { ConfirmDelete } from './ConfirmDelete'
import { MovePicker } from './MovePicker'
import { NewFolderDialog } from './NewFolder'
import { Refusals, type RefusalsProps } from './Refusals'
import { readRows } from './rows'

export interface Organising {
  /** The doc # whose title is a field now */
  renaming: number | undefined
  startRename: (docNumber: number) => void
  /** Ends the rename (saved or not) and gives the focus back to the row, unless `focusRow` is false (the person left the field) */
  stopRename: (docNumber: number, focusRow?: boolean) => void
  /** Asks first, then moves the documents to the trash */
  askDelete: (documents: Movable[]) => void
  /** Opens the picker, then moves the documents where it's told */
  askMove: (documents: Movable[]) => void
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

/** The row of a document in the open list, if it's drawn */
function rowOf(docNumber: number): HTMLElement | null {
  return globalThis.document.querySelector<HTMLElement>(`[data-row-key="d${docNumber}"]`)
}

/** The key of the row that follows the documents' rows in the list drawn now, else the one before them, else none */
function rowAfter(docNumbers: number[]): string | undefined {
  const gone = new Set(docNumbers.map((n) => `d${n}`))
  const keys = Array.from(globalThis.document.querySelectorAll('[role="grid"] [data-row-key]'), (row) => row.getAttribute('data-row-key') ?? '')
  const last = keys.findLastIndex((key) => gone.has(key))
  if (last < 0) return undefined
  return keys.slice(last + 1).find((key) => !gone.has(key)) ?? keys.slice(0, last).findLast((key) => !gone.has(key))
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
 * Renaming, moving and deleting documents, and making folders (spec 6.7): what is being renamed, the dialogs that ask
 * and the one that says what was refused. Each document is its own call, so a refusal leaves the others done.
 */
export function OrganisingProvider({ children }: { children: ReactNode }) {
  const api = useApi()
  const queryClient = useQueryClient()
  const me = useMe()
  const { selected, select } = useSelection()
  const [renaming, setRenaming] = useState<number | undefined>()
  const [deleting, setDeleting] = useState<Movable[] | undefined>()
  const [moving, setMoving] = useState<Movable[] | undefined>()
  const [parent, setParent] = useState<Place | undefined>()
  const [refused, setRefused] = useState<Pick<RefusalsProps, 'title' | 'lines'> | undefined>()

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
    const next = rowAfter(todo.map((one) => one.docNumber))
    const outcome = await moveDocuments(api, todo, folder.id)
    await invalidateDocumentLists(queryClient)
    report(outcome, plural('moved', outcome.done.length, { folder: folder.name || t('move.thisFolder') }), t('move.refusedTitle'))
    if (outcome.done.length > 0 && !dragged) focusLater(next)
  }

  async function remove(documents: readonly Movable[]) {
    const next = rowAfter(documents.map((one) => one.docNumber))
    const outcome = await removeDocuments(api, documents)
    // What was deleted can't stay selected: its details would answer 404
    if (selected !== undefined && outcome.done.some((one) => one.docNumber === selected)) select(undefined)
    await invalidateDocumentLists(queryClient)
    report(outcome, plural('deleted', outcome.done.length), t('delete.refusedTitle'))
    // Where the focus goes once the rows are gone: the next row, or the one before, or the list. With none gone, it stays
    // where it was, as the dialog that says why gives it back there
    if (outcome.done.length > 0) focusLater(next)
  }

  const value: Organising = {
    renaming,
    startRename: setRenaming,
    stopRename: (docNumber, focusRow = true) => {
      setRenaming(undefined)
      // The field is gone once this has rendered; the row is where the focus belongs, unless the person put it elsewhere
      if (focusRow) requestAnimationFrame(() => rowOf(docNumber)?.focus())
    },
    askDelete: setDeleting,
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
          documents={deleting}
          onCancel={() => setDeleting(undefined)}
          onConfirm={() => {
            const documents = deleting
            setDeleting(undefined)
            void remove(documents)
          }}
        />
      )}
      {moving && (
        <MovePicker
          documents={moving}
          onCancel={() => setMoving(undefined)}
          onChoose={(folder) => {
            const documents = moving
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
