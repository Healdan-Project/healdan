// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { components } from '@healdan/api'
import { formatDate, formatVersion, Icon, t, type TableColumn, type TableRow } from '@healdan/ui'
import { computerLabel } from '../actions/checkout'
import { HoldMarker } from '../actions/HoldMarker'
import type { SearchScope } from '../search/scopes'
import { itemIcon } from '../kindIcons'
import { DocumentTitle, FolderName } from '../organising/RenameField'
import { childKey, selectable, type Hit } from './useListing'
import styles from './views.module.css'

type S = components['schemas']

export interface ListRow extends TableRow {
  item: Hit
}

/** A row of a list. A plain click opens a folder, as it always has: Shift- or Cmd-click, the arrows and Space select it. */
export function rowOf(item: Hit): ListRow {
  const selects = selectable(item)
  return { id: childKey(item), textValue: item.type === 'matter' ? item.display : item.name, selectable: selects, opensOnClick: selects && item.type === 'folder', item }
}

// Title shows the file name: CE has no title of its own, and the server sorts Title by it (plan correction 7). A held
// document's row ends it with the hold's marker.
const title: TableColumn<ListRow> = {
  id: 'title',
  name: t('list.title'),
  isRowHeader: true,
  allowsSorting: true,
  width: '3fr',
  cell: ({ item }) => (
    <>
      <Icon {...itemIcon(item)} />
      {item.type === 'document' ? (
        <DocumentTitle document={item} className={styles.name} />
      ) : item.type === 'folder' ? (
        <FolderName folder={item} place="row" className={styles.name} />
      ) : (
        <span className={styles.name}>{item.display}</span>
      )}
      {item.type === 'document' && <HoldMarker document={item} />}
    </>
  ),
}
const docNumber: TableColumn<ListRow> = { id: 'docNumber', name: t('list.docNumber'), allowsSorting: true, align: 'end', width: 96, cell: ({ item }) => item.type === 'document' && String(item.docNumber) }
const version: TableColumn<ListRow> = { id: 'version', name: t('list.version'), allowsSorting: true, align: 'end', width: 84, cell: ({ item }) => item.type === 'document' && formatVersion(item.fileVersion) }
const author: TableColumn<ListRow> = { id: 'author', name: t('list.author'), allowsSorting: true, width: '1fr', cell: ({ item }) => item.type === 'document' && item.author?.displayName }
const edited: TableColumn<ListRow> = { id: 'edited', name: t('list.edited'), allowsSorting: true, width: 132, cell: ({ item }) => item.type === 'document' && formatDate(item.lastModified) }

/** A folder's list: Title, Doc #, Version, Author and Edit Date. */
export const listColumns: TableColumn<ListRow>[] = [title, docNumber, version, author, edited]

/**
 * "1001.1 – Charter Dispute", as the server formats it and the left pane's Recent Matters shows it (list columns spec
 * 4.1). The name is the cell's own text, so a keyboard and a screen reader reach it as a pointer does; a matter with no
 * name shows its number. The tooltip is the whole of it and the client, "1001.1 – Charter Dispute · Saltmarsh Shipping",
 * so a name the column cuts short can still be read by pointing at it.
 */
export function MatterCell({ matter }: { matter: S['MatterRef'] }) {
  return (
    <span className={styles.matterCell} title={`${matter.display} · ${matter.client.name}`}>
      {matter.display}
    </span>
  )
}

function matterOf(item: Hit): S['MatterRef'] | null {
  return item.type === 'matter' ? null : item.matter
}

// Built when a list draws, so the firm's own word for a matter (setLabels) is the heading. A share of the spare width,
// as Title and Author have, so a wider window shows more of the matter's name (list columns spec 4.1).
function matterColumn(): TableColumn<ListRow> {
  return { id: 'matter', name: t('list.matter'), width: '1.5fr', minWidth: 100, cell: ({ item }) => {
    const matter = matterOf(item)
    return matter && <MatterCell matter={matter} />
  } }
}

/** The columns a list of search hits or recent items has: the scope decides which. */
export function hitColumns(scope: SearchScope): TableColumn<ListRow>[] {
  if (scope === 'matters' || scope === 'clients') {
    return [
      { ...title, name: t('list.matter'), allowsSorting: false },
      { id: 'client', name: t('list.client'), width: '1fr', cell: ({ item }) => item.type === 'matter' && item.client.name },
    ]
  }
  if (scope === 'folders') return [{ ...title, width: '2fr' }, matterColumn()]
  // Title first, sharing the spare width with Matter and Author; the others are narrower here than in a folder, and each
  // ends in an ellipsis when it doesn't fit
  return [{ ...title, width: '2fr', minWidth: 220 }, { ...docNumber, width: 80 }, { ...version, width: 70 }, matterColumn(), author, { ...edited, width: 116 }]
}

/** What a list keeps while the reading pane is open and the list is narrow (spec section 6.4): Title, Doc # and Edit Date. */
export function narrowed(columns: TableColumn<ListRow>[]): TableColumn<ListRow>[] {
  return columns.filter((column) => column.id === 'title' || column.id === 'docNumber' || column.id === 'edited')
}

/**
 * "MacBook · Word": the computer, then the app muted after a dot, on one line, as a Standard row's 34 pixels have room
 * for one. Each is an element of its own, whose text is just the computer or the app; the dot is drawn only, and the
 * spaces around it keep the two apart for a screen reader. The whole text is a tooltip too, as the Matter cell's name
 * is, so an app the ellipsis cuts short can be read by pointing at it.
 */
function ComputerCell({ hold }: { hold: S['HoldView'] | null }) {
  const { computer, app } = computerLabel(hold)
  return (
    <span className={styles.computerCell} title={app ? `${computer} · ${app}` : computer}>
      <span>{computer}</span>
      {app && (
        <>
          {' '}
          <span className={styles.muted} aria-hidden="true">
            ·
          </span>{' '}
          <span className={styles.muted}>{app}</span>
        </>
      )}
    </span>
  )
}

/** Checked Out to Me: the server's order (latest check-out first), so nothing sorts, as its list takes no `onSort`. */
export function checkedOutColumns(): TableColumn<ListRow>[] {
  return [
    { ...title, width: '2fr', minWidth: 220 },
    { ...docNumber, width: 80 },
    { ...version, width: 70 },
    matterColumn(),
    {
      id: 'computer',
      name: t('list.computer'),
      width: 130,
      cell: ({ item }) => item.type === 'document' && <ComputerCell hold={item.hold} />,
    },
    { id: 'checkedOut', name: t('list.checkedOut'), width: 116, cell: ({ item }) => item.type === 'document' && item.hold?.since ? formatDate(item.hold.since) : null },
    { ...edited, width: 116 },
  ]
}
