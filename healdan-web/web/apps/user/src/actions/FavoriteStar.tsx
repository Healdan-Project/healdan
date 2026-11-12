// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { apiPath, type components } from '@healdan/api'
import { Button, Icon, toast } from '@healdan/ui'
import { messageFor } from '../frame/errors'
import { keys, useFavorites } from '../queries'
import { useApi } from '../services'
import styles from './actions.module.css'

type S = components['schemas']

export type Starred = { folderId: number } | { docNumber: number }

function isIt(favorite: S['FavoriteView'], what: Starred): boolean {
  const item = favorite.item
  if ('folderId' in what) return item.type === 'folder' && item.id === what.folderId
  return item.type === 'document' && item.docNumber === what.docNumber
}

/**
 * The star of a folder (beside its name) or of a document (beside its name in the details): in My Favorites or not
 * (spec 4, 6.1). Its state is My Favorites' own list, so the two never disagree.
 */
export function FavoriteStar({ what, add, remove }: { what: Starred; add: string; remove: string }) {
  const api = useApi()
  const queryClient = useQueryClient()
  const favorites = useFavorites()
  const [busy, setBusy] = useState(false)
  const favorite = favorites.data?.items.find((one) => isIt(one, what))

  async function toggle() {
    setBusy(true)
    try {
      if (favorite) await api.send('DELETE', apiPath`/v1/me/favorites/${favorite.id}`)
      else await api.send('POST', '/v1/me/favorites', what)
      await queryClient.invalidateQueries({ queryKey: keys.favorites })
    } catch (error) {
      toast(messageFor(error))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Button variant="quiet" aria-label={favorite ? remove : add} isDisabled={busy || favorites.isPending} onPress={() => void toggle()}>
      <span className={favorite ? styles.starred : styles.star}>
        <Icon name={favorite ? 'starFilled' : 'star'} size={18} />
      </span>
    </Button>
  )
}
