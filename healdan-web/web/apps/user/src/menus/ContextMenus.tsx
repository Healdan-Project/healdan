// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useNavigate } from '@tanstack/react-router'
import type { components } from '@healdan/api'
import { MenuItem, MenuSeparator, t } from '@healdan/ui'
import { ActionItem } from '../actions/ActionControls'
import { useFavorite, type Starred } from '../actions/FavoriteStar'
import { useMyMattersChange } from '../actions/myMatters'
import { useDocumentActions } from '../actions/useDocumentActions'
import { placeOf } from '../filing/dropTargets'
import { mayMakeFolderIn } from '../organising/NewFolder'
import { useOrganising } from '../organising/OrganisingProvider'
import { useMe, useMyMatters } from '../queries'

type S = components['schemas']

// The right-click menus (changed after M6: the owner's dogfooding punch list, 2026-11-14). Each offers what the toolbar,
// More, a star or the left pane's + and ⋯ offer for the same thing, from the same rules; none is the only way to do
// anything. Where the item has an address in the app, Open in New Tab ends the menu's first group, as the browser's own
// menu for a link would have offered it.

/** A link to the item's page in a new tab */
function NewTab({ href }: { href: string }) {
  return (
    <MenuItem id="openInNewTab" href={href} target="_blank" rel="noopener">
      {t('menu.openInNewTab')}
    </MenuItem>
  )
}

/**
 * A document's row: the toolbar's Open, Preview, Download and Check Out (Check In and Release instead, for the person's
 * own web check-out), More's Rename, Move To…, Delete and Copy Link, and Favorites. With several selected only Move To…
 * and Delete are enabled, as on the toolbar.
 */
export function DocumentMenuItems({ documents }: { documents: S['DocumentView'][] }) {
  const actions = useDocumentActions({ document: documents.length === 1 ? documents[0] : undefined, documents })
  return (
    <>
      <ActionItem action={actions.open} />
      <ActionItem action={actions.preview} />
      <ActionItem action={actions.openInNewTab} />
      <MenuSeparator />
      <ActionItem action={actions.download} />
      {actions.checkIn ? (
        <>
          <ActionItem action={actions.checkIn} />
          <ActionItem action={actions.release} />
        </>
      ) : (
        <ActionItem action={actions.checkOut} />
      )}
      <MenuSeparator />
      <ActionItem action={actions.rename} />
      <ActionItem action={actions.move} />
      <ActionItem action={actions.delete} />
      <MenuSeparator />
      <ActionItem action={actions.copyLink} />
      <ActionItem action={actions.favorite} />
    </>
  )
}

/** The words of Favorites' item: Add to, or Remove from once it's there */
function favoriteLabel(favorite: ReturnType<typeof useFavorite>): string {
  return favorite.favorite ? t('favorites.remove') : t('favorites.add')
}

/**
 * A folder's row or tree item: Open, New Folder where the toolbar offers it (mayMakeFolderIn: inside a matter, never in a
 * search folder), and Favorites, as its star does. Renaming, moving and deleting folders are a later phase (spec 16).
 */
export function FolderMenuItems({ folder }: { folder: S['FolderView'] }) {
  const navigate = useNavigate()
  const me = useMe()
  const organising = useOrganising()
  const favorite = useFavorite({ folderId: folder.id })
  const place = placeOf(folder)
  return (
    <>
      <MenuItem id="open" onAction={() => void navigate({ to: '/folders/$id', params: { id: String(folder.id) } })}>
        {t('details.open')}
      </MenuItem>
      <NewTab href={`/app/folders/${folder.id}`} />
      {mayMakeFolderIn(place, me.data?.admin) && (
        <>
          <MenuSeparator />
          <MenuItem id="newFolder" onAction={() => organising.askNewFolder(place)}>
            {t('newFolder.button')}
          </MenuItem>
        </>
      )}
      <MenuSeparator />
      <MenuItem id="favorite" isDisabled={favorite.isDisabled} onAction={() => void favorite.toggle()}>
        {favoriteLabel(favorite)}
      </MenuItem>
    </>
  )
}

/** A matter (in My Matters, Recent Matters or a search's list): Open, and Add to or Remove from My Matters, as + and ⋯ do */
export function MatterMenuItems({ folderId }: { folderId: number }) {
  const navigate = useNavigate()
  const mine = useMyMatters()
  const change = useMyMattersChange()
  const kept = mine.data?.items.some((matter) => matter.folderId === folderId) === true
  return (
    <>
      <MenuItem id="open" onAction={() => void navigate({ to: '/folders/$id', params: { id: String(folderId) } })}>
        {t('details.open')}
      </MenuItem>
      <NewTab href={`/app/folders/${folderId}`} />
      <MenuSeparator />
      <MenuItem id="myMatters" isDisabled={!mine.isSuccess} onAction={() => void change(kept ? 'DELETE' : 'PUT', folderId)}>
        {kept ? t('myMatters.remove') : t('myMatters.addMenu')}
      </MenuItem>
    </>
  )
}

/** An item of My Favorites: Open, as its link does, and Remove from Favorites */
export function FavoriteMenuItems({ favorite }: { favorite: S['FavoriteView'] }) {
  const navigate = useNavigate()
  const item = favorite.item
  const star: Starred = item.type === 'document' ? { docNumber: item.docNumber } : { folderId: item.id }
  const bookmark = useFavorite(star)
  const open = () =>
    item.type === 'document' ? void navigate({ to: '/doc/$ref', params: { ref: String(item.docNumber) } }) : void navigate({ to: '/folders/$id', params: { id: String(item.id) } })
  return (
    <>
      <MenuItem id="open" onAction={open}>
        {t('details.open')}
      </MenuItem>
      <NewTab href={item.type === 'document' ? `/app/doc/${item.docNumber}` : `/app/folders/${item.id}`} />
      <MenuSeparator />
      <MenuItem id="favorite" isDisabled={bookmark.isDisabled} onAction={() => void bookmark.toggle()}>
        {t('favorites.remove')}
      </MenuItem>
    </>
  )
}

/** A folder of any kind: a matter's top level has the matter's menu, and a Store, which opens as no folder, has none */
export function folderMenu(folder: S['FolderView']) {
  if (folder.kind === 'store') return undefined
  if (folder.kind === 'matter') return <MatterMenuItems folderId={folder.id} />
  return <FolderMenuItems folder={folder} />
}
