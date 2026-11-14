// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useIsMutating, useMutation, useQueryClient } from '@tanstack/react-query'
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

export interface Favorite {
  /** The bookmark, while the folder or document is in My Favorites */
  favorite: S['FavoriteView'] | undefined
  /** My Favorites isn't known yet, or a change to this one is under way (from the star or a menu alike) */
  isDisabled: boolean
  /** Adds it to My Favorites, or takes it out */
  toggle: () => Promise<void>
}

/** What marks a change to one favorite while it runs, so the star and a menu that offer it both wait */
const favoriteKey = (what: Starred | undefined) => ['favorite', what && ('folderId' in what ? `f${what.folderId}` : `d${what.docNumber}`)] as const

/**
 * Whether a folder or a document is in My Favorites, and the change to it: My Favorites' own list is its state, so the
 * star, a menu and the list never disagree (spec 4, 6.1). Nothing is offered for none.
 */
export function useFavorite(what: Starred | undefined): Favorite {
  const api = useApi()
  const queryClient = useQueryClient()
  const favorites = useFavorites()
  const favorite = what && favorites.data?.items.find((one) => isIt(one, what))
  const busy = useIsMutating({ mutationKey: favoriteKey(what) }) > 0 && what !== undefined
  const mutation = useMutation({
    mutationKey: favoriteKey(what),
    mutationFn: async ({ bookmark, star }: { bookmark: S['FavoriteView'] | undefined; star: Starred }) => {
      try {
        if (bookmark) await api.send('DELETE', apiPath`/v1/me/favorites/${bookmark.id}`)
        else await api.send('POST', '/v1/me/favorites', star)
        await queryClient.invalidateQueries({ queryKey: keys.favorites })
      } catch (error) {
        toast(messageFor(error))
      }
    },
  })
  return {
    favorite,
    isDisabled: what === undefined || busy || favorites.isPending,
    toggle: async () => {
      if (what) await mutation.mutateAsync({ bookmark: favorite, star: what })
    },
  }
}

/**
 * The star of a folder (beside its name) or of a document (beside its name in the details): in My Favorites or not
 * (spec 4, 6.1).
 */
export function FavoriteStar({ what, add, remove }: { what: Starred; add: string; remove: string }) {
  const { favorite, isDisabled, toggle } = useFavorite(what)
  return (
    <Button variant="quiet" aria-label={favorite ? remove : add} isDisabled={isDisabled} onPress={() => void toggle()}>
      <span className={favorite ? styles.starred : styles.star}>
        <Icon name={favorite ? 'starFilled' : 'star'} size={18} />
      </span>
    </Button>
  )
}
