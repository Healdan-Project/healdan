// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQueryClient } from '@tanstack/react-query'
import { useEffect } from 'react'
import type { components } from '@healdan/api'
import { t } from '@healdan/ui'
import { invalidateDocumentLists, invalidateFolderNames } from '../actions/refresh'
import { extensionOf, folderRenameRefusal, renameDocument, renameFolder, renameRefusal } from '../actions/rename'
import type { Movable } from '../actions/batch'
import { useMe } from '../queries'
import { useApi } from '../services'
import { useOrganising, type RenamePlace } from './OrganisingProvider'
import styles from './organising.module.css'
import { useNameField } from './useNameField'

type S = components['schemas']

/**
 * A document's title in a list: its name, or while it's being renamed (F2, or More › Rename) a field in its place
 * (spec 6.7). It keeps its extension, so the name without it is selected.
 */
export function DocumentTitle({ document, className }: { document: Movable; className?: string }) {
  const { renaming } = useOrganising()
  if (renaming?.key !== `d${document.docNumber}` || renaming.place !== 'row') return <span className={className}>{document.name}</span>
  return <DocumentRenameField document={document} />
}

/** The field alone asks for the API and who is signed in: every row of a list draws a title, and one at most a field */
function DocumentRenameField({ document }: { document: Movable }) {
  const { stopRename } = useOrganising()
  const api = useApi()
  const me = useMe()
  const queryClient = useQueryClient()
  const extension = extensionOf(document.name)
  return (
    <RenameField
      name={document.name}
      selected={extension ? document.name.length - extension.length - 1 : document.name.length}
      save={async (name) => {
        let renamed = false
        try {
          await renameDocument(api, document.docNumber, name)
          renamed = true
        } finally {
          // Whether it worked or not, what shows the document is asked again; an email showing it as a copy only if it did
          void invalidateDocumentLists(queryClient, renamed ? [document.docNumber] : [])
        }
      }}
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
  const { renaming } = useOrganising()
  if (renaming?.key !== `f${folder.id}` || renaming.place !== place) return <span className={className}>{folder.name}</span>
  return <FolderRenameField folder={folder} place={place} />
}

function FolderRenameField({ folder, place }: { folder: S['FolderView']; place: RenamePlace }) {
  const { stopRename } = useOrganising()
  const api = useApi()
  const queryClient = useQueryClient()
  return (
    <RenameField
      name={folder.name}
      selected={folder.name.length}
      save={async (name) => {
        await renameFolder(api, folder.id, name)
        // What shows folder names is asked again; a refusal changed none
        void invalidateFolderNames(queryClient)
      }}
      refusal={folderRenameRefusal}
      onDone={stopRename}
      // The tree is narrow: what is wrong goes below the field there
      stacked={place === 'tree'}
    />
  )
}

interface RenameFieldProps {
  /** The name as it is: the field's first text, and its label's */
  name: string
  /** How much of the name, from its start, is selected when the field opens */
  selected: number
  /** Saves the new name, and asks again for what shows it; a refusal throws */
  save: (name: string) => Promise<void>
  /** What to say of a refusal; `typed` is the name sent */
  refusal: (error: unknown, typed: string) => string
  /** The rename is over; `focusBack`: the field had the focus, which goes back where the rename began */
  onDone: (focusBack: boolean) => void
  /** What is wrong goes below the field, wrapping, rather than beside it: for a narrow place */
  stacked?: boolean
}

/**
 * Enter saves, Esc cancels, and leaving the field saves what was changed. A refusal keeps the field open with what is
 * wrong beside it (`useNameField`, which a favorite's name in My Favorites shares).
 */
function RenameField({ name, selected, save, refusal, onDone, stacked = false }: RenameFieldProps) {
  /** `fromKey`: Enter saved, so the focus is the field's to give back; a save on leaving leaves it where the person put it */
  function submit(fromKey: boolean) {
    const typed = field.text.trim()
    if (typed === name) return field.stop(fromKey)
    void field.run(
      fromKey,
      () => save(typed),
      (failure) => refusal(failure, typed),
    )
  }
  const field = useNameField({
    initial: name,
    onEnter: () => {
      if (field.text.trim() === '') field.setError(t('fields.required'))
      else submit(true)
    },
    onLeave: () => submit(false),
    onDone,
  })
  const input = field.input

  useEffect(() => {
    const element = input.current
    if (!element) return
    // React Aria's tree sends a focus that enters it from outside (the ⋯ menu's popover) to its focused row, which can be
    // another one: the field's own row takes the focus first, as a right-click menu's does when it opens
    if (globalThis.document.activeElement !== element) element.closest<HTMLElement>('[role="treegrid"] [role="row"]')?.focus({ preventScroll: true })
    element.focus()
    element.setSelectionRange(0, selected)
  }, [selected, input])

  return (
    <span className={stacked ? `${styles.rename} ${styles.stacked}` : styles.rename}>
      <input
        {...field.props}
        className={styles.renameInput}
        aria-label={t('rename.field', { name })}
        // A press or a double-click in the field is the field's: it doesn't select the row, open it or open the tree's folder
        onPointerDown={(event) => event.stopPropagation()}
        onMouseDown={(event) => event.stopPropagation()}
        onClick={(event) => event.stopPropagation()}
        onDoubleClick={(event) => event.stopPropagation()}
      />
      {field.error && (
        <span id={field.errorId} role="alert" className={styles.renameError}>
          {field.error}
        </span>
      )}
    </span>
  )
}
