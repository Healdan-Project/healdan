// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQueryClient } from '@tanstack/react-query'
import { useEffect, useId, useRef, useState } from 'react'
import type { components } from '@healdan/api'
import { t } from '@healdan/ui'
import { invalidateDocumentLists } from '../actions/refresh'
import { extensionOf, folderRenameRefusal, renameDocument, renameFolder, renameRefusal } from '../actions/rename'
import type { Movable } from '../actions/batch'
import { useMe } from '../queries'
import { useApi } from '../services'
import { useOrganising, type RenamePlace } from './OrganisingProvider'
import styles from './organising.module.css'

type S = components['schemas']

/**
 * A document's title in a list: its name, or while it's being renamed (F2, or More › Rename) a field in its place
 * (spec 6.7). It keeps its extension, so the name without it is selected.
 */
export function DocumentTitle({ document, className }: { document: Movable; className?: string }) {
  const { renaming, stopRename } = useOrganising()
  const api = useApi()
  const me = useMe()
  if (renaming?.key !== `d${document.docNumber}` || renaming.place !== 'row') return <span className={className}>{document.name}</span>
  const extension = extensionOf(document.name)
  return (
    <RenameField
      name={document.name}
      selected={extension ? document.name.length - extension.length - 1 : document.name.length}
      save={(name) => renameDocument(api, document.docNumber, name)}
      refusal={(error) => renameRefusal(error, document.name, me.data?.id)}
      onDone={stopRename}
    />
  )
}

/**
 * A folder's name where it shows (a list's row, the My Matters tree, the open folder's heading), or while it's being
 * renamed there a field in its place (folder rename spec 5.3), with all of the name selected.
 */
export function FolderName({ folder, place, className }: { folder: S['FolderView']; place: RenamePlace; className?: string }) {
  const { renaming, stopRename } = useOrganising()
  const api = useApi()
  if (renaming?.key !== `f${folder.id}` || renaming.place !== place) return <span className={className}>{folder.name}</span>
  return <RenameField name={folder.name} selected={folder.name.length} save={(name) => renameFolder(api, folder.id, name)} refusal={folderRenameRefusal} onDone={stopRename} />
}

interface RenameFieldProps {
  /** The name as it is: the field's first text, and its label's */
  name: string
  /** How much of the name, from its start, is selected when the field opens */
  selected: number
  /** Saves the new name; a refusal throws */
  save: (name: string) => Promise<unknown>
  /** What to say of a refusal; `typed` is the name sent */
  refusal: (error: unknown, typed: string) => string
  /** The rename is over; `focusBack`: the field had the focus, which goes back where the rename began */
  onDone: (focusBack: boolean) => void
}

/**
 * Enter saves, Esc cancels, and leaving the field saves what was changed. A refusal keeps the field open with what is
 * wrong beside it. Whether it worked or not, what shows names is asked for again.
 */
function RenameField({ name, selected, save, refusal, onDone }: RenameFieldProps) {
  const queryClient = useQueryClient()
  const [text, setText] = useState(name)
  const [error, setError] = useState<string>()
  const input = useRef<HTMLInputElement>(null)
  const errorId = useId()
  // Enter saves and the field goes, which makes it lose the focus: that must not save again
  const finished = useRef(false)
  const saving = useRef(false)

  useEffect(() => {
    const field = input.current
    if (!field) return
    field.focus()
    field.setSelectionRange(0, selected)
  }, [selected])

  /** `fromKey`: Enter saved, so the focus is the field's to give back; a save on leaving leaves it where the person put it */
  async function submit(fromKey: boolean) {
    if (finished.current || saving.current) return
    const typed = text.trim()
    if (typed === name) return stop(fromKey)
    saving.current = true
    try {
      await save(typed)
      stop(fromKey)
    } catch (failure) {
      setError(refusal(failure, typed))
      if (fromKey) input.current?.focus()
    } finally {
      saving.current = false
      void invalidateDocumentLists(queryClient)
    }
  }

  function stop(focusBack = true) {
    finished.current = true
    onDone(focusBack)
  }

  return (
    <span className={styles.rename}>
      <input
        ref={input}
        className={styles.renameInput}
        value={text}
        aria-label={t('rename.field', { name })}
        aria-invalid={error ? true : undefined}
        aria-describedby={error ? errorId : undefined}
        onChange={(event) => {
          setText(event.target.value)
          setError(undefined)
        }}
        onKeyDown={(event) => {
          // Not the list's or the tree's: Space, Delete, the arrows and typing are the field's own while it has the focus
          event.stopPropagation()
          if (event.key === 'Enter') {
            event.preventDefault()
            if (text.trim() === '') setError(t('fields.required'))
            else void submit(true)
          } else if (event.key === 'Escape') {
            event.preventDefault()
            stop()
          }
        }}
        onBlur={() => {
          // Leaving the field with nothing changed, nothing in it, or a refusal showing is a cancel; with a new name, a save
          if (text.trim() === '' || error) stop(false)
          else void submit(false)
        }}
        // A press or a double-click in the field is the field's: it doesn't select the row, open it or open the tree's folder
        onPointerDown={(event) => event.stopPropagation()}
        onMouseDown={(event) => event.stopPropagation()}
        onClick={(event) => event.stopPropagation()}
        onDoubleClick={(event) => event.stopPropagation()}
      />
      {error && (
        <span id={errorId} role="alert" className={styles.renameError}>
          {error}
        </span>
      )}
    </span>
  )
}
