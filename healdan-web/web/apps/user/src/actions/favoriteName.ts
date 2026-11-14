// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { ApiError, apiPath, type Api, type components } from '@healdan/api'
import { t } from '@healdan/ui'
import { messageFor } from '../frame/errors'

type S = components['schemas']

// A favorite's own name (spec 6.1, changed after M6: the owner's dogfooding punch list, 2026-11-14). It is the bookmark's
// title, which the Classic Interface's Bookmarks shows too, and only its owner sees it. The server sorts My Favorites by it.

/** Gives the favorite a name of its own, or with null puts back its item's own name */
export function renameFavorite(api: Api, id: number, name: string | null): Promise<S['FavoriteView']> {
  return api.send('PATCH', apiPath`/v1/me/favorites/${id}`, { name })
}

/** Whether the favorite has a name of its own, which Use Original Name takes away */
export function hasOwnName(favorite: S['FavoriteView']): boolean {
  return favorite.name !== favorite.item.name
}

/**
 * What the favorite really is, and where: its item's own name and its matter (a Store's name for a folder outside one),
 * such as "Documents · 1001.1 – Harbour Lease", as its tooltip
 */
export function favoriteTooltip(favorite: S['FavoriteView']): string {
  const item = favorite.item
  const where = item.type === 'document' ? item.matter?.display : (item.matter?.display ?? item.store?.name)
  return where ? t('favorites.where', { name: item.name, where }) : item.name
}

/** What to tell the person of a refused name, from the catalogue: the server's own text is never shown */
export function favoriteNameRefusal(error: unknown): string {
  if (error instanceof ApiError && error.code === 'validation') {
    const fields = (error.body as { fields?: { field?: unknown; code?: unknown }[] } | null)?.fields
    const named = (code: string) => fields?.some((one) => one?.field === 'name' && one.code === code) === true
    if (named('too-long')) return t('favorites.tooLong')
    if (named('invalid')) return t('favorites.invalid')
  }
  return messageFor(error)
}
