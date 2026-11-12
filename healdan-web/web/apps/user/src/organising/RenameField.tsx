// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQueryClient } from '@tanstack/react-query'
import { useEffect, useId, useRef, useState } from 'react'
import { t } from '@healdan/ui'
import { invalidateDocumentLists } from '../actions/refresh'
import { extensionOf, renameDocument, renameRefusal } from '../actions/rename'
import type { Movable } from '../actions/batch'
import { useApi } from '../services'
import { useOrganising } from './OrganisingProvider'
import styles from './organising.module.css'

/**
 * A document's title in a list: its name, or while it's being renamed (F2, or More › Rename) a field in its place
 * (spec 6.7). Enter saves, Esc cancels, and leaving the field saves what was changed. A refusal keeps the field open
 * with what is wrong beside it.
 */
export function DocumentTitle({ document, className }: { document: Movable; className?: string }) {
  const { renaming } = useOrganising()
  return renaming === document.docNumber ? <RenameField document={document} /> : <span className={className}>{document.name}</span>
}

function RenameField({ document }: { document: Movable }) {
  const api = useApi()
  const queryClient = useQueryClient()
  const { stopRename } = useOrganising()
  const [text, setText] = useState(document.name)
  const [error, setError] = useState<string>()
  const input = useRef<HTMLInputElement>(null)
  const errorId = useId()
  // Enter saves and the field goes, which makes it lose the focus: that must not save again
  const finished = useRef(false)
  const saving = useRef(false)

  // The name without its extension is selected, as Finder does
  useEffect(() => {
    const field = input.current
    if (!field) return
    field.focus()
    // The row isn't dragged while its title is a field, so selecting text in the field starts no drag of the row. Set on the
    // element, not through the row, which would draw the row again as the field takes the focus.
    const row = field.closest<HTMLElement>('[data-row-key]')
    if (row) row.draggable = false
    const extension = extensionOf(document.name)
    field.setSelectionRange(0, extension ? document.name.length - extension.length - 1 : document.name.length)
    return () => {
      if (row) row.draggable = true
    }
  }, [document.name])

  async function save() {
    if (finished.current || saving.current) return
    const name = text.trim()
    if (name === document.name) return stop()
    saving.current = true
    try {
      await renameDocument(api, document.docNumber, name)
      stop()
    } catch (failure) {
      setError(renameRefusal(failure, document.name))
      input.current?.focus()
    } finally {
      saving.current = false
      // Whether it worked or not, what shows the document is asked again
      void invalidateDocumentLists(queryClient)
    }
  }

  function stop() {
    finished.current = true
    stopRename(document.docNumber)
  }

  return (
    <span className={styles.rename}>
      <input
        ref={input}
        className={styles.renameInput}
        value={text}
        aria-label={t('rename.field', { name: document.name })}
        aria-invalid={error ? true : undefined}
        aria-describedby={error ? errorId : undefined}
        onChange={(event) => {
          setText(event.target.value)
          setError(undefined)
        }}
        onKeyDown={(event) => {
          // Not the list's: Space, Delete and the arrows are the field's own while it has the focus
          event.stopPropagation()
          if (event.key === 'Enter') {
            event.preventDefault()
            if (text.trim() === '') setError(t('fields.required'))
            else void save()
          } else if (event.key === 'Escape') {
            event.preventDefault()
            stop()
          }
        }}
        onBlur={() => {
          // Leaving the field with nothing changed, nothing in it, or a refusal showing is a cancel; with a new name, a save
          if (text.trim() === '' || error) stop()
          else void save()
        }}
        // A press or a double-click in the field is the field's: it doesn't select the row or open it
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
