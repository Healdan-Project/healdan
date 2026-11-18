// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { components } from '@healdan/api'
import { Button, formatDateTime, formatSize, Icon, plural, t } from '@healdan/ui'
import type { TrashViewName } from '../actions/trash'
import { PropertyList, type PropertyRow } from '../details/PropertyList'
import { fileIcon, folderIcon } from '../kindIcons'
import { matterParts } from '../matterDisplay'
import detailsStyles from '../details/details.module.css'
import styles from './views.module.css'

type S = components['schemas']

/** The days until the night the server destroys a removed item, by the calendar: "in 23 days" */
export function daysLeft(destroyAfter: string, now = new Date()): number {
  const day = (date: Date) => new Date(date.getFullYear(), date.getMonth(), date.getDate()).getTime()
  return Math.max(0, Math.round((day(new Date(destroyAfter)) - day(now)) / (24 * 60 * 60 * 1000)))
}

/** Under a week, Destroyed is in the caution colour (folders and Trash spec 7.3) */
export const CAUTION_DAYS = 7

/** Destroyed, "in 23 days", in the caution colour under a week; nothing for an item that isn't removed */
export function DestroyIn({ destroyAfter }: { destroyAfter: string | null }) {
  if (!destroyAfter) return null
  const days = daysLeft(destroyAfter)
  return <span className={days < CAUTION_DAYS ? styles.caution : undefined}>{plural('trash.destroyIn', days)}</span>
}

/**
 * Where it was deleted from: "1001.1 › Drafts" in the list, by the matter's number, or "1001.1 – Charter Dispute › Drafts"
 * in full in the details. The path runs from the Store down, so a matter's folder is its second step; without a matter it
 * is the path as it is.
 */
export function deletedFromText(item: S['TrashItem'], full = false): string {
  const path = item.deletedFrom.path
  if (!item.matter || path.length < 2) return path.join(' › ')
  return [full ? item.matter.display : matterParts(item.matter.display).number, ...path.slice(2)].join(' › ')
}

/** Where a restore put it, as the toast says: "1001.1 › Drafts", by its matter's number and the folders inside the matter */
export function placeText(place: S['FolderView']): string {
  const folders = place.path.filter((step) => step.kind === 'folder').map((step) => step.name)
  const head = place.matter ? matterParts(place.matter.display).number : place.path[0]?.name
  return [...(head ? [head] : []), ...folders].join(' › ')
}

/** How many items a folder holds, beside its name and in its details: only what the person can read (spec 4.2) */
export function itemCount(contents: S['TrashContents']): number {
  return contents.folders + contents.documents
}

export function trashIcon(item: S['TrashItem']) {
  return item.type === 'folder' ? folderIcon() : fileIcon(item.name)
}

export interface TrashDetailsProps {
  item: S['TrashItem']
  view: TrashViewName
  /** What's running (Restore, Remove or Empty Trash): one at a time, so the others take no press */
  busy?: 'restore' | 'remove' | 'empty'
  onRestore: () => void
  /** None in Removed, which has Restore only */
  onRemove?: () => void
}

/**
 * The selected item's details (folders and Trash spec 7.2): its icon, name, kind and size or count, Deleted From in full,
 * Deleted, and what a folder holds, with Restore and Remove…. Everyone's adds who deleted it, and Removed who removed it,
 * when, and when it's destroyed.
 */
export function TrashDetails({ item, view, busy, onRestore, onRemove }: TrashDetailsProps) {
  const folder = item.type === 'folder'
  const kind = folder ? t('trash.folder') : t(`filters.type.${item.kind === 'folder' ? 'other' : item.kind}`)
  const amount = folder ? (item.contents ? plural('trash.items', itemCount(item.contents)) : undefined) : item.size !== null ? formatSize(item.size) : undefined
  const rows: PropertyRow[] = [{ label: t('trash.deletedFrom'), value: deletedFromText(item, true) }]
  if (view !== 'mine') rows.push({ label: t('trash.deletedBy'), value: item.deletedBy?.name })
  rows.push({ label: t('trash.deleted'), value: formatDateTime(item.deletedAt) })
  if (view === 'removed') {
    rows.push({ label: t('trash.removedBy'), value: item.removedBy?.name ?? t('trash.notRecorded') })
    rows.push({ label: t('trash.removed'), value: item.removedAt && formatDateTime(item.removedAt) })
    rows.push({ label: t('trash.destroyed'), value: <DestroyIn destroyAfter={item.destroyAfter} /> })
  }
  if (item.contents) {
    rows.push({
      label: t('trash.holds'),
      value: t('holds.list', { folders: plural('holds.folders', item.contents.folders), documents: plural('holds.documents', item.contents.documents) }),
    })
  }
  return (
    <div className={detailsStyles.panel}>
      <div className={styles.trashIcon}>
        <Icon {...trashIcon(item)} size={48} />
      </div>
      <div className={detailsStyles.header}>
        <h2 className={detailsStyles.name}>{item.name}</h2>
      </div>
      <p className={detailsStyles.meta}>{[kind, amount].filter(Boolean).join(' · ')}</p>
      <div className={detailsStyles.actions}>
        <Button variant="default" isPending={busy === 'restore'} isDisabled={busy !== undefined && busy !== 'restore'} onPress={onRestore}>
          {t('trash.restore')}
        </Button>
        {onRemove && (
          <Button isPending={busy === 'remove'} isDisabled={busy !== undefined && busy !== 'remove'} onPress={onRemove}>
            {t('trash.remove')}
          </Button>
        )}
      </div>
      <PropertyList rows={rows} />
    </div>
  )
}
