// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useIsMutating, useMutation, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from '@tanstack/react-router'
import type { components } from '@healdan/api'
import { MenuItem, MenuSeparator, t, toast } from '@healdan/ui'
import { ActionItem } from '../actions/ActionControls'
import type { OrganisingItems } from '../actions/batch'
import { favoriteNameRefusal, hasOwnName, renameFavorite } from '../actions/favoriteName'
import { useFavorite, type Starred } from '../actions/FavoriteStar'
import { anyDeletable, anyMovable, movingFolder } from '../actions/folders'
import { useMyMattersChange } from '../actions/myMatters'
import { favoriteAction, newTabAction, useDocumentActions } from '../actions/useDocumentActions'
import { adminMatterHref, docHref, folderHref } from '../addresses'
import { placeOf } from '../filing/dropTargets'
import { mayMakeFolderIn } from '../organising/NewFolder'
import { useOrganising, type RenamePlace } from '../organising/OrganisingProvider'
import { keys, useMe, useMyMatters } from '../queries'
import { useApi } from '../services'

type S = components['schemas']

// The right-click menus (changed after M6: the owner's dogfooding punch list, 2026-11-14). Each offers what the toolbar,
// More, a star or the left pane's + and ⋯ offer for the same thing, from the same rules; none is the only way to do
// anything. Where the item has an address in the app, Open in New Tab ends the menu's first group, as the browser's own
// menu for a link would have offered it.

/**
 * A document's row: the toolbar's Open, Preview, Download and Check Out (Check In and Release instead, for the person's
 * own web check-out; Release… for one another of their computers holds), More's Rename, Move To…, Delete and Copy Link,
 * and Favorites. With several selected, Move To… and Delete are enabled, and Release when they're all the person's own
 * check-outs, as on the toolbar.
 */
export function DocumentMenuItems({ documents }: { documents: S['DocumentView'][] }) {
  const one = documents.length === 1 ? documents[0] : undefined
  const actions = useDocumentActions({ document: one, documents })
  // Only the menu offers these: Favorites is the details' star's change, with one document
  const favorite = favoriteAction(useFavorite(one && { docNumber: one.docNumber }))
  return (
    <>
      <ActionItem action={actions.open} />
      <ActionItem action={actions.preview} />
      <ActionItem action={newTabAction(one && docHref(one.docNumber))} />
      <MenuSeparator />
      <ActionItem action={actions.download} />
      {actions.checkIn || actions.release ? (
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
      <ActionItem action={favorite} />
    </>
  )
}

/**
 * A folder's row or tree item: Open, New Folder where the toolbar offers it (mayMakeFolderIn: inside a matter, never in a
 * search folder), Rename, Move To… and Delete (each greyed out where the folder's `canRename`, `canMove` or `canDelete`
 * says no: folder rename spec 5.2, folders and Trash spec 7.5), and Favorites, as its star does. `renameIn` is where the
 * field opens.
 */
export function FolderMenuItems({ folder, renameIn }: { folder: S['FolderView']; renameIn: RenamePlace }) {
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
      <ActionItem action={newTabAction(folderHref(folder.id))} />
      <MenuSeparator />
      {mayMakeFolderIn(place, me.data?.admin) && (
        <MenuItem id="newFolder" onAction={() => organising.askNewFolder(place)}>
          {t('newFolder.button')}
        </MenuItem>
      )}
      {/* Greyed out where the server says it can't be renamed: F2 says why */}
      <MenuItem id="rename" isDisabled={!folder.canRename} onAction={() => organising.startRename(`f${folder.id}`, renameIn)}>
        {t('rename.menu')}
      </MenuItem>
      <FolderChangeItems folder={folder} />
      <MenuSeparator />
      <ActionItem action={favoriteAction(favorite)} />
    </>
  )
}

/**
 * Move To… and Delete for one folder, greyed out where its `canMove` and `canDelete` say no (folders and Trash spec 7.5): in
 * its right-click menu, the tree's ⋯ and the open folder's heading ⋯, after Rename. A matter's own folder has neither.
 */
export function FolderChangeItems({ folder }: { folder: S['FolderView'] }) {
  const organising = useOrganising()
  const items: OrganisingItems = { documents: [], folders: [movingFolder(folder)] }
  return (
    <>
      <MenuItem id="move" isDisabled={!folder.canMove} onAction={() => organising.askMove(items)}>
        {t('move.menu')}
      </MenuItem>
      <MenuItem id="delete" isDisabled={!folder.canDelete} onAction={() => organising.askDelete(items)}>
        {t('delete.menu')}
      </MenuItem>
    </>
  )
}

/**
 * A selection of several with a folder in it: Move To… and Delete, which take folders and documents together, each its own
 * call (folders and Trash spec 7.5). Each is there while anything in it can be moved or deleted: a folder that can't is
 * said in Refusals while the rest is moved or deleted.
 */
export function SelectionMenuItems({ items }: { items: OrganisingItems }) {
  const organising = useOrganising()
  return (
    <>
      <MenuItem id="move" isDisabled={!anyMovable(items)} onAction={() => organising.askMove(items)}>
        {t('move.menu')}
      </MenuItem>
      <MenuItem id="delete" isDisabled={!anyDeletable(items)} onAction={() => organising.askDelete(items)}>
        {t('delete.menu')}
      </MenuItem>
    </>
  )
}

/**
 * A matter (in My Matters, Recent Matters or a search's list): Open, Open in New Tab, Rename greyed out (a matter's own folder
 * takes the matter's name), and Add to or Remove from My Matters, as + and ⋯ do; for an administrator, Manage in Admin, the
 * matter's page in the Admin app (`matterId` is the matter's own id, not its folder's), in a new tab, as Open in New Tab opens
 * it. A matter's own folder whose answer names no matter (`matterId` null) has the rest, and no Manage in Admin.
 */
export function MatterMenuItems({ folderId, matterId }: { folderId: number; matterId: number | null }) {
  const navigate = useNavigate()
  // Only decides what's offered: the Admin app asks the server again
  const admin = useMe().data?.admin === true
  const mine = useMyMatters()
  const change = useMyMattersChange()
  const kept = mine.data?.items.some((matter) => matter.folderId === folderId) === true
  return (
    <>
      <MenuItem id="open" onAction={() => void navigate({ to: '/folders/$id', params: { id: String(folderId) } })}>
        {t('details.open')}
      </MenuItem>
      <ActionItem action={newTabAction(folderHref(folderId))} />
      <MenuSeparator />
      {/* A matter's own folder takes the matter's name: Edit, on the matter's page in the Admin app (folder rename spec 5.2) */}
      <MenuItem id="rename" isDisabled>
        {t('rename.menu')}
      </MenuItem>
      <MenuItem id="myMatters" isDisabled={!mine.isSuccess} onAction={() => void change(kept ? 'DELETE' : 'PUT', folderId)}>
        {kept ? t('myMatters.remove') : t('myMatters.addMenu')}
      </MenuItem>
      {admin && matterId !== null && (
        <>
          <MenuSeparator />
          <ActionItem action={{ id: 'manageInAdmin', label: t('menu.manageInAdmin'), isDisabled: false, href: adminMatterHref(matterId) }} />
        </>
      )}
    </>
  )
}

/**
 * An item of My Favorites: Open, as its link does; Rename Favorite, as F2 does, and Use Original Name once it has a name of its
 * own; and Remove from Favorites (its star's change, which follows its state)
 */
export function FavoriteMenuItems({ favorite, onRename }: { favorite: S['FavoriteView']; onRename: () => void }) {
  const navigate = useNavigate()
  const item = favorite.item
  const star: Starred = item.type === 'document' ? { docNumber: item.docNumber } : { folderId: item.id }
  const bookmark = useFavorite(star)
  const original = useOriginalName(favorite)
  const open = () =>
    item.type === 'document' ? void navigate({ to: '/doc/$ref', params: { ref: String(item.docNumber) } }) : void navigate({ to: '/folders/$id', params: { id: String(item.id) } })
  return (
    <>
      <MenuItem id="open" onAction={open}>
        {t('details.open')}
      </MenuItem>
      <ActionItem action={newTabAction(item.type === 'document' ? docHref(item.docNumber) : folderHref(item.id))} />
      <MenuSeparator />
      <MenuItem id="rename" isDisabled={original.isPending} onAction={onRename}>
        {t('favorites.rename')}
      </MenuItem>
      {hasOwnName(favorite) && (
        <MenuItem id="originalName" isDisabled={original.isPending} onAction={() => void original.put()}>
          {t('favorites.useOriginalName')}
        </MenuItem>
      )}
      <ActionItem action={favoriteAction(bookmark)} />
    </>
  )
}

/** Use Original Name: the favorite takes its item's name back, and follows it again */
function useOriginalName(favorite: S['FavoriteView']) {
  const api = useApi()
  const queryClient = useQueryClient()
  const key = ['favoriteName', favorite.id] as const
  const isPending = useIsMutating({ mutationKey: key }) > 0
  const mutation = useMutation({
    mutationKey: key,
    mutationFn: async () => {
      try {
        await renameFavorite(api, favorite.id, null)
      } catch (error) {
        toast(favoriteNameRefusal(error))
      } finally {
        await queryClient.invalidateQueries({ queryKey: keys.favorites })
      }
    },
  })
  return { isPending, put: () => mutation.mutateAsync() }
}

/** A folder of any kind: a matter's top level has the matter's menu, and a Store, which opens as no folder, has none */
export function folderMenu(folder: S['FolderView'], renameIn: RenamePlace) {
  if (folder.kind === 'store') return undefined
  if (folder.kind === 'matter') return <MatterMenuItems folderId={folder.id} matterId={folder.matter?.id ?? null} />
  return <FolderMenuItems folder={folder} renameIn={renameIn} />
}
