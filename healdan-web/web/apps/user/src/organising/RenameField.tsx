// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQueryClient } from '@tanstack/react-query'
import { useEffect } from 'react'
import { t } from '@healdan/ui'
import { invalidateDocumentLists } from '../actions/refresh'
import { extensionOf, renameDocument, renameRefusal } from '../actions/rename'
import type { Movable } from '../actions/batch'
import { useMe } from '../queries'
import { useApi } from '../services'
import { useOrganising } from './OrganisingProvider'
import styles from './organising.module.css'
import { useNameField } from './useNameField'

/**
 * A document's title in a list: its name, or while it's being renamed (F2, or More › Rename) a field in its place
 * (spec 6.7). Enter saves, Esc cancels, and leaving the field saves what was changed. A refusal keeps the field open
 * with what is wrong beside it (`useNameField`, which a favorite's name in My Favorites shares).
 */
export function DocumentTitle({ document, className }: { document: Movable; className?: string }) {
  const { renaming } = useOrganising()
  return renaming === document.docNumber ? <RenameField document={document} /> : <span className={className}>{document.name}</span>
}

function RenameField({ document }: { document: Movable }) {
  const api = useApi()
  const queryClient = useQueryClient()
  const { stopRename } = useOrganising()
  const me = useMe()
  /** `fromKey`: Enter saved, so the focus is the field's to give back; a save on leaving leaves it where the person put it */
  function save(fromKey: boolean) {
    const name = field.text.trim()
    if (name === document.name) return field.stop(fromKey)
    void field.run(
      fromKey,
      async () => {
        try {
          await renameDocument(api, document.docNumber, name)
        } finally {
          // Whether it worked or not, what shows the document is asked again
          void invalidateDocumentLists(queryClient)
        }
      },
      (failure) => renameRefusal(failure, document.name, me.data?.id),
    )
  }
  const field = useNameField({
    initial: document.name,
    onEnter: () => {
      if (field.text.trim() === '') field.setError(t('fields.required'))
      else save(true)
    },
    onLeave: () => save(false),
    // `focus`: the field had the focus, which goes to the row; not when the person left the field for somewhere else
    onDone: (focus) => stopRename(document.docNumber, focus),
  })
  const input = field.input

  // The name without its extension is selected, as Finder does
  useEffect(() => {
    const element = input.current
    if (!element) return
    element.focus()
    const extension = extensionOf(document.name)
    element.setSelectionRange(0, extension ? document.name.length - extension.length - 1 : document.name.length)
  }, [document.name, input])

  return (
    <span className={styles.rename}>
      <input
        {...field.props}
        className={styles.renameInput}
        aria-label={t('rename.field', { name: document.name })}
        // A press or a double-click in the field is the field's: it doesn't select the row or open it
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
