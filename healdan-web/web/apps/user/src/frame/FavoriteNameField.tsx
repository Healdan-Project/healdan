// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQueryClient } from '@tanstack/react-query'
import { useEffect, useId, useRef, useState, type ReactNode } from 'react'
import type { components } from '@healdan/api'
import { t } from '@healdan/ui'
import { favoriteNameRefusal, hasOwnName, renameFavorite } from '../actions/favoriteName'
import { keys } from '../queries'
import { useApi } from '../services'
import styles from './LeftPane.module.css'

type S = components['schemas']

/**
 * A favorite's name being changed in My Favorites (F2, or the favorite's Rename…), in place of its link, as a document's
 * title is in a list (spec 6.1, 6.7). Enter saves, and with nothing in it puts back the item's own name; Esc cancels;
 * leaving the field saves a new name. A refusal keeps the field open with what is wrong under it. `onDone` is told whether
 * the focus is the field's to give back to the link.
 */
export function FavoriteNameField({ favorite, icon, onDone }: { favorite: S['FavoriteView']; icon: ReactNode; onDone: (focusLink: boolean) => void }) {
  const api = useApi()
  const queryClient = useQueryClient()
  const [text, setText] = useState(favorite.name)
  const [error, setError] = useState<string>()
  const input = useRef<HTMLInputElement>(null)
  const errorId = useId()
  // Enter saves and the field goes, which makes it lose the focus: that must not save again
  const finished = useRef(false)
  const saving = useRef(false)

  useEffect(() => {
    input.current?.focus()
    input.current?.select()
  }, [])

  function stop(focusLink: boolean) {
    finished.current = true
    onDone(focusLink)
  }

  async function save(fromKey: boolean) {
    if (finished.current || saving.current) return
    const name = text.trim()
    // The same name, or nothing for a favorite that has its item's name already, changes nothing
    if (name === favorite.name || (name === '' && !hasOwnName(favorite))) return stop(fromKey)
    saving.current = true
    try {
      await renameFavorite(api, favorite.id, name === '' ? null : name)
    } catch (failure) {
      saving.current = false
      setError(favoriteNameRefusal(failure))
      if (fromKey) input.current?.focus()
      void queryClient.invalidateQueries({ queryKey: keys.favorites })
      return
    }
    // My Favorites is asked again before the link comes back, so it comes back in the place its new name gives it: the
    // server sorts by name, and a link moved after it has the focus can lose it
    await queryClient.invalidateQueries({ queryKey: keys.favorites })
    saving.current = false
    stop(fromKey)
  }

  return (
    <div className={styles.renaming}>
      <div className={styles.renamingRow}>
        {icon}
        <input
          ref={input}
          className={styles.renameInput}
          value={text}
          aria-label={t('favorites.renameField', { name: favorite.name })}
          aria-invalid={error ? true : undefined}
          aria-describedby={error ? errorId : undefined}
          onChange={(event) => {
            setText(event.target.value)
            setError(undefined)
          }}
          onKeyDown={(event) => {
            // Not the pane's: Shift+F10 and the arrows are the field's own while it has the focus
            event.stopPropagation()
            if (event.key === 'Enter') {
              event.preventDefault()
              void save(true)
            } else if (event.key === 'Escape') {
              event.preventDefault()
              stop(true)
            }
          }}
          onBlur={() => {
            // Leaving the field with nothing changed, nothing in it, or a refusal showing is a cancel; with a new name, a save
            if (text.trim() === '' || error) stop(false)
            else void save(false)
          }}
        />
      </div>
      {error && (
        <span id={errorId} role="alert" className={styles.renameError}>
          {error}
        </span>
      )}
    </div>
  )
}
