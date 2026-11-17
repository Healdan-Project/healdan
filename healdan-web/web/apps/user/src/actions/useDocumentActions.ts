// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { components } from '@healdan/api'
import { t, type IconName } from '@healdan/ui'
import { APP_BASE } from '../addresses'
import { useOrganising } from '../organising/OrganisingProvider'
import { useQuickLook } from '../preview/QuickLookState'
import { checkInAccept } from './checkout'
import { copyLink } from './copyLink'
import type { Favorite } from './FavoriteStar'
import { anyDeletable, type MovingFolder } from './folders'
import { useOpenInApp } from './OpenInAppState'
import { pickFile } from './pickFile'
import { useCheckout } from './useCheckout'

type DocumentView = components['schemas']['DocumentView']

/** One thing that can be done with the selection: what a toolbar's button, More's item and a right-click menu's item show and do */
export interface DocumentAction {
  id: string
  label: string
  /** The toolbar's button shows it */
  icon?: IconName
  isDisabled: boolean
  perform: () => void
}

/** An action that is a link, which a menu offers as one: Open in New Tab */
export interface LinkAction {
  id: string
  label: string
  isDisabled: boolean
  href: string
}

export interface DocumentActions {
  open: DocumentAction
  preview: DocumentAction
  download: DocumentAction
  /** Disabled unless the document is free and the person may check it out */
  checkOut: DocumentAction
  /** Only for the person's own check-out from the web page (R14), which Check In and Release end */
  checkIn?: DocumentAction
  release?: DocumentAction
  rename: DocumentAction
  move: DocumentAction
  delete: DocumentAction
  copyLink: DocumentAction
}

export interface DocumentActionsOf {
  /** The one document Open, Download and Check Out act on: none with nothing or several selected */
  document: DocumentView | undefined
  /** The documents Move To… and Delete act on (the selection); Rename and Copy Link need exactly one */
  documents: DocumentView[]
  /** The folders selected with them, which Move To… and Delete take too (folders and Trash spec 7.5) */
  folders?: readonly MovingFolder[]
  /** The document Preview opens, when it isn't `document`'s: the toolbar's is the address's, whose row may not be drawn yet */
  preview?: number
}

/**
 * What can be done with the selected documents (spec 6.1, 6.7): the one source of the toolbar's buttons, its More (⋯) and
 * a row's right-click menu, so their labels, the rules that disable them and what they do never differ. Actions on one
 * document are disabled with none or several; Move To… and Delete take any number. While a check-out runs, whichever
 * control started it, each control that offers it is disabled. Favorites and Open in New Tab are only in the menus, which
 * make them with favoriteAction and newTabAction, so a toolbar doesn't watch My Favorites for an action it never shows.
 */
export function useDocumentActions({ document, documents, folders = [], preview = document?.docNumber }: DocumentActionsOf): DocumentActions {
  const app = useOpenInApp()
  const quickLook = useQuickLook()
  const organising = useOrganising()
  const checkout = useCheckout(document)
  const one = documents.length === 1 && folders.length === 0 ? documents[0] : undefined
  const none = documents.length + folders.length === 0
  const may = document?.canOpen === true
  const yours = checkout.state.kind === 'yours'

  return {
    open: { id: 'open', label: t('details.open'), icon: 'external', isDisabled: !may, perform: () => document && app.open(document) },
    preview: { id: 'preview', label: t('preview.button'), icon: 'eye', isDisabled: preview === undefined, perform: () => preview !== undefined && quickLook.open(preview) },
    download: { id: 'download', label: t('details.download'), icon: 'download', isDisabled: !may, perform: () => document && app.download(document) },
    checkOut: {
      id: 'checkOut',
      label: t('checkout.checkOut'),
      icon: 'pencil',
      isDisabled: !document || checkout.busy || checkout.state.kind !== 'free' || !document.canEdit,
      perform: () => void checkout.checkOut(),
    },
    checkIn:
      document && yours
        ? {
            id: 'checkIn',
            label: t('checkout.checkIn'),
            isDisabled: checkout.busy,
            perform: () =>
              void pickFile({ accept: checkInAccept(document.name), label: t('checkout.file') }).then((file) => file && checkout.checkIn(file)),
          }
        : undefined,
    release: document && yours ? { id: 'release', label: t('checkout.release'), isDisabled: checkout.busy, perform: () => void checkout.release() } : undefined,
    rename: { id: 'rename', label: t('rename.menu'), isDisabled: !one, perform: () => one && organising.startRename(`d${one.docNumber}`) },
    // A folder that says it can't be moved greys Move To… out, as its own menus do. Delete is there while anything selected
    // can be deleted: a folder of several that can't is said in Refusals while the rest is deleted (folders and Trash spec 7.5)
    move: { id: 'move', label: t('move.menu'), isDisabled: none || folders.some((folder) => !folder.canMove), perform: () => organising.askMove({ documents, folders }) },
    delete: { id: 'delete', label: t('delete.menu'), isDisabled: !anyDeletable({ documents, folders }), perform: () => organising.askDelete({ documents, folders }) },
    copyLink: { id: 'copyLink', label: t('details.copyLink'), isDisabled: !one, perform: () => one && void copyLink(one.docNumber) },
  }
}

/**
 * Add to Favorites, or Remove from Favorites once it's there, as the star (useFavorite) says: every menu's item for it,
 * disabled while a change runs, as a menu item has no pending state
 */
export function favoriteAction(favorite: Favorite): DocumentAction {
  return { id: 'favorite', label: favorite.favorite ? t('favorites.remove') : t('favorites.add'), isDisabled: favorite.isUnknown || favorite.isBusy, perform: () => void favorite.toggle() }
}

/** Open in New Tab: the item's own page (docHref, folderHref), disabled with none, as with several documents selected */
export function newTabAction(href: string | undefined): LinkAction {
  return { id: 'openInNewTab', label: t('menu.openInNewTab'), isDisabled: href === undefined, href: href ?? `${APP_BASE}/` }
}
