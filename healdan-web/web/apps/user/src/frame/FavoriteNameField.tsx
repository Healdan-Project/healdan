// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQueryClient } from '@tanstack/react-query'
import { useEffect, type ReactNode } from 'react'
import type { components } from '@healdan/api'
import { t } from '@healdan/ui'
import { favoriteNameRefusal, hasOwnName, renameFavorite } from '../actions/favoriteName'
import organising from '../organising/organising.module.css'
import { useNameField } from '../organising/useNameField'
import { keys } from '../queries'
import { useApi } from '../services'
import styles from './LeftPane.module.css'

type S = components['schemas']

/**
 * A favorite's name being changed in My Favorites (F2, or the favorite's Rename Favorite), in place of its link, as a document's
 * title is in a list (spec 6.1, 6.7). Enter saves, and with nothing in it puts back the item's own name; Esc cancels;
 * leaving the field saves a new name. A refusal keeps the field open with what is wrong under it. `onDone` is told whether
 * the focus is the field's to give back to the link. The keys and the field's style are a document title's
 * (`useNameField`, `organising.module.css`).
 */
export function FavoriteNameField({ favorite, icon, onDone }: { favorite: S['FavoriteView']; icon: ReactNode; onDone: (focusLink: boolean) => void }) {
  const api = useApi()
  const queryClient = useQueryClient()
  function save(fromKey: boolean) {
    const name = field.text.trim()
    // The name it opened with, or nothing for a favorite that has its item's name already, changes nothing
    if (name === field.opened || (name === '' && !hasOwnName(favorite))) return field.stop(fromKey)
    void field.run(
      fromKey,
      async () => {
        try {
          await renameFavorite(api, favorite.id, name === '' ? null : name)
        } catch (failure) {
          void queryClient.invalidateQueries({ queryKey: keys.favorites })
          throw failure
        }
        // My Favorites is asked again before the link comes back, so it comes back in the place its new name gives it:
        // the server sorts by name, and a link moved after it has the focus can lose it
        await queryClient.invalidateQueries({ queryKey: keys.favorites })
      },
      favoriteNameRefusal,
    )
  }
  const field = useNameField({ initial: favorite.name, onEnter: () => save(true), onLeave: () => save(false), onDone })
  const input = field.input

  useEffect(() => {
    input.current?.focus()
    input.current?.select()
  }, [input])

  return (
    <div className={styles.renaming}>
      <div className={styles.renamingRow}>
        {icon}
        <input {...field.props} className={organising.renameInput} aria-label={t('favorites.renameField', { name: favorite.name })} />
      </div>
      {field.error && (
        <span id={field.errorId} role="alert" className={styles.renameError}>
          {field.error}
        </span>
      )}
    </div>
  )
}
