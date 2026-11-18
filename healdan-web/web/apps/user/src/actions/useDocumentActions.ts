// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { components } from '@healdan/api'
import { t, type IconName } from '@healdan/ui'
import { APP_BASE } from '../addresses'
import { useOrganising } from '../organising/OrganisingProvider'
import { useQuickLook } from '../preview/QuickLookState'
import { useMe } from '../queries'
import { checkInAccept, holdState } from './checkout'
import { copyLink } from './copyLink'
import type { Favorite } from './FavoriteStar'
import { anyDeletable, anyMovable, type MovingFolder } from './folders'
import { useOpenInApp } from './OpenInAppState'
import { pickFile } from './pickFile'
import { useReleasing } from './ReleaseProvider'
import { useCheckout, useHoldsBusy } from './useCheckout'

type DocumentView = components['schemas']['DocumentView']

/** One thing that can be done with the selection: what a toolbar's button, More's item and a right-click menu's item show and do */
export interface DocumentAction {
  id: string
  label: string
  /** The toolbar's button shows it */
  icon?: IconName
  isDisabled: boolean
  /**
   * A change it makes is under way: a toolbar's button is pending, so it keeps the focus while it takes no press, and a
   * menu's item, which has no pending state, is disabled
   */
  isPending?: boolean
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
  /** Only for the person's own check-out from the web page (R14) */
  checkIn?: DocumentAction
  /**
   * Only when every document is the person's own check-out: Release… when another of their computers holds any, which
   * warns first, and Release when the web page holds them all
   */
  release?: DocumentAction
  rename: DocumentAction
  move: DocumentAction
  delete: DocumentAction
  copyLink: DocumentAction
}

export interface DocumentActionsOf {
  /** The one document Open, Download and Check Out act on: none with nothing or several selected */
  document: DocumentView | undefined
  /** The documents Release, Move To… and Delete act on (the selection); Rename and Copy Link need exactly one */
  documents: DocumentView[]
  /** The folders selected with them, which Move To… and Delete take too (folders and Trash spec 7.5) */
  folders?: readonly MovingFolder[]
  /** The document Preview opens, when it isn't `document`'s: the toolbar's is the address's, whose row may not be drawn yet */
  preview?: number
}

/**
 * What can be done with the selected documents (spec 6.1, 6.7): the one source of the toolbar's buttons, its More (⋯) and
 * a row's right-click menu, so their labels, the rules that disable them and what they do never differ. Actions on one
 * document are disabled with none or several; Release, Move To… and Delete take any number. While a check-out or a
 * release runs, whichever control started it, each control that offers one is pending. Favorites and Open in New Tab
 * are only in the menus, which make them with favoriteAction and newTabAction, so a toolbar doesn't watch My Favorites
 * for an action it never shows.
 */
export function useDocumentActions({ document, documents, folders = [], preview = document?.docNumber }: DocumentActionsOf): DocumentActions {
  const app = useOpenInApp()
  const quickLook = useQuickLook()
  const organising = useOrganising()
  const releasing = useReleasing()
  const me = useMe()
  const checkout = useCheckout(document)
  const releaseBusy = useHoldsBusy(documents.map((one) => one.docNumber))
  const one = documents.length === 1 && folders.length === 0 ? documents[0] : undefined
  const none = documents.length + folders.length === 0
  const may = document?.canOpen === true
  const yours = checkout.state.kind === 'yours'
  const holds = documents.map((each) => holdState(each.hold, me.data?.id).kind)
  // Everything selected is the person's own check-out: a folder selected with them isn't one
  const releasable = folders.length === 0 && holds.length > 0 && holds.every((kind) => kind === 'yours' || kind === 'yourDevice')

  return {
    open: { id: 'open', label: t('details.open'), icon: 'external', isDisabled: !may, perform: () => document && app.open(document) },
    preview: { id: 'preview', label: t('preview.button'), icon: 'eye', isDisabled: preview === undefined, perform: () => preview !== undefined && quickLook.open(preview) },
    download: { id: 'download', label: t('details.download'), icon: 'download', isDisabled: !may, perform: () => document && app.download(document) },
    // Check Out and Release share the toolbar's button, which turns from one to the other while it's pending
    checkOut: {
      id: 'checkOut',
      label: t('checkout.checkOut'),
      icon: 'pencil',
      isDisabled: !document || checkout.state.kind !== 'free' || !document.canEdit,
      isPending: checkout.busy,
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
    release: releasable
      ? {
          id: 'release',
          label: holds.includes('yourDevice') ? t('checkout.releaseAsk') : t('checkout.release'),
          icon: 'lockOpen',
          isDisabled: false,
          isPending: releaseBusy,
          perform: () => releasing.askRelease(documents),
        }
      : undefined,
    rename: { id: 'rename', label: t('rename.menu'), isDisabled: !one, perform: () => one && organising.startRename(`d${one.docNumber}`) },
    // Move To… and Delete are there while anything selected can be moved or deleted: a folder of several that can't is said
    // in Refusals while the rest is moved or deleted (folders and Trash spec 7.5)
    move: { id: 'move', label: t('move.menu'), isDisabled: none || !anyMovable({ documents, folders }), perform: () => organising.askMove({ documents, folders }) },
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
