// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { components } from '@healdan/api'
import { formatDate, formatDateTime, formatSize, formatVersion, Icon, t, VisuallyHidden, type TableColumn, type TableRow } from '@healdan/ui'
import { computerLabel } from '../actions/checkout'
import { HoldMarker } from '../actions/HoldMarker'
import type { SearchScope } from '../search/scopes'
import { typeLabel } from '../filters/FiltersPanel'
import { itemIcon } from '../kindIcons'
import { DocumentTitle, FolderName } from '../organising/RenameField'
import type { DocumentListKind, ListKind } from './columnLayouts'
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

/**
 * "1001.1 – Charter Dispute", as the server formats it and the left pane's Recent Matters shows it (list columns spec
 * 4.1). The name is the cell's own text, so a keyboard and a screen reader reach it as a pointer does; a matter with no
 * name shows its number. The tooltip is the whole of it and the client, "1001.1 – Charter Dispute · Saltmarsh Shipping",
 * so a name the column cuts short can still be read by pointing at it. The client is in the cell's text for a screen
 * reader too, not drawn, as a tooltip can't be reached by keyboard; a sighted keyboard user finds it in the details.
 * Where the Client column shows, `withClient` is false and the text is left out, so a screen reader doesn't read the client
 * twice.
 */
export function MatterCell({ matter, withClient = true }: { matter: S['MatterRef']; withClient?: boolean }) {
  return (
    <span className={styles.oneLine} title={`${matter.display} · ${matter.client.name}`}>
      {matter.display}
      {withClient && <VisuallyHidden>{` · ${matter.client.name}`}</VisuallyHidden>}
    </span>
  )
}

function matterOf(item: Hit): S['MatterRef'] | null {
  return item.type === 'matter' ? null : item.matter
}

/** Where a document sits: its folder's name, with the matter in the tooltip, so a name the column cuts short can be read */
function LocationCell({ document }: { document: S['DocumentView'] }) {
  const { folder, matter } = document
  return (
    <span className={styles.oneLine} title={matter ? `${folder.name} · ${matter.display}` : folder.name}>
      {folder.name}
    </span>
  )
}

/**
 * "MacBook · Word": the computer, then the app muted after a dot, on one line, as a Standard row's 34 pixels have room
 * for one. Each is an element of its own, whose text is just the computer or the app; the dot is drawn only, and the
 * spaces around it keep the two apart for a screen reader. The whole text is a tooltip too, as the Matter cell's whole
 * matter is, so an app the ellipsis cuts short can be read by pointing at it.
 */
function ComputerCell({ hold }: { hold: S['HoldView'] | null }) {
  const { computer, app } = computerLabel(hold)
  return (
    <span className={styles.oneLine} title={app ? `${computer} · ${app}` : computer}>
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

/**
 * Every document column (spec 4.7), with the widths a folder's list has; `columnsFor` gives the other lists theirs. A
 * folder's own row leaves a document's columns blank. Matter's heading is the firm's own word for a matter, so it is read
 * when a list draws (`setLabels`).
 */
export const documentColumns: Record<string, TableColumn<ListRow>> = {
  title,
  docNumber: { id: 'docNumber', name: t('list.docNumber'), allowsSorting: true, align: 'end', width: 96, cell: ({ item }) => item.type === 'document' && String(item.docNumber) },
  version: { id: 'version', name: t('list.version'), allowsSorting: true, align: 'end', width: 84, cell: ({ item }) => item.type === 'document' && formatVersion(item.fileVersion) },
  matter: {
    id: 'matter',
    get name() {
      return t('list.matter')
    },
    allowsSorting: true,
    width: '1.5fr',
    minWidth: 100,
    cell: ({ item }) => {
      const matter = matterOf(item)
      return matter && <MatterCell matter={matter} />
    },
  },
  client: { id: 'client', name: t('list.client'), allowsSorting: true, width: '1fr', cell: ({ item }) => item.type !== 'matter' && item.matter?.client.name },
  location: { id: 'location', name: t('list.location'), allowsSorting: true, width: '1fr', cell: ({ item }) => item.type === 'document' && <LocationCell document={item} /> },
  author: { id: 'author', name: t('list.author'), allowsSorting: true, width: '1fr', cell: ({ item }) => item.type === 'document' && item.author?.displayName },
  filed: { id: 'filed', name: t('list.filed'), allowsSorting: true, newestFirst: true, width: 116, cell: ({ item }) => item.type === 'document' && formatDate(item.dateFiled) },
  size: { id: 'size', name: t('list.size'), allowsSorting: true, align: 'end', width: 84, cell: ({ item }) => item.type === 'document' && formatSize(item.size) },
  type: { id: 'type', name: t('list.type'), allowsSorting: true, width: 112, cell: ({ item }) => item.type === 'document' && typeLabel(item.kind) },
  edited: { id: 'edited', name: t('list.edited'), allowsSorting: true, newestFirst: true, width: 132, cell: ({ item }) => item.type === 'document' && formatDate(item.lastModified) },
  // Wide enough for a date and a time: "Nov 12, 2026, 2:32 PM"
  lastUsed: { id: 'lastUsed', name: t('list.lastUsed'), allowsSorting: true, newestFirst: true, width: 168, cell: ({ item }) => item.type !== 'matter' && item.lastUsed ? formatDateTime(item.lastUsed) : null },
  computer: {
    id: 'computer',
    name: t('list.computer'),
    allowsSorting: true,
    width: 130,
    cell: ({ item }) => item.type === 'document' && <ComputerCell hold={item.hold} />,
  },
  checkedOut: { id: 'checkedOut', name: t('list.checkedOut'), allowsSorting: true, newestFirst: true, width: 116, cell: ({ item }) => item.type === 'document' && item.hold?.since ? formatDate(item.hold.since) : null },
}

const COMMON = ['title', 'docNumber', 'version', 'author', 'edited', 'matter', 'client', 'location', 'filed', 'size', 'type']
// Spec 4.7's table order: the columns every list offers, then Last Used (Recent) or Computer and Checked Out (Checked Out to Me)
const OFFERED_ORDER = ['title', 'docNumber', 'version', 'author', 'edited', 'matter', 'client', 'location', 'filed', 'size', 'type', 'lastUsed', 'computer', 'checkedOut']

/** The columns a list of documents offers, in spec 4.7's order. The kind is the list's own, never the layout beside the reading pane, which only picks among them. */
export function offeredFor(kind: DocumentListKind): string[] {
  if (kind === 'beside') return [...COMMON]
  return OFFERED_ORDER.filter((id) => COMMON.includes(id) || (id === 'lastUsed' && (kind === 'recentDocuments' || kind === 'recentEmails')) || ((id === 'computer' || id === 'checkedOut') && kind === 'checkedOut'))
}

/** What the server can sort a paged list by yet (spec 6); the other columns sort only a list the browser holds whole */
export const SERVER_SORTS: ReadonlySet<string> = new Set(['title', 'docNumber', 'version', 'author', 'edited'])

// A list's own default widths, over the folder list's (which `documentColumns` has). Every other list shares the search
// results' narrower ones: Title shares the spare width with Matter and Author, and the rest end in an ellipsis.
function widthsOf(kind: ListKind, id: string): Partial<TableColumn<ListRow>> {
  if (kind === 'folder') return {}
  switch (id) {
    case 'title':
      return kind === 'folders' ? { width: '2fr' } : { width: '2fr', minWidth: 220 }
    case 'docNumber':
      return { width: 80 }
    case 'version':
      return { width: 70 }
    case 'edited':
      return { width: 116 }
    case 'matter':
      // Checked Out to Me's list is wider than the room beside the details anyway, so Matter needn't give way below its old 130
      return kind === 'checkedOut' ? { minWidth: 130 } : {}
    default:
      return {}
  }
}

/**
 * The columns shown, in that order, with the list's own widths. A paged list (`serverSorted`) sorts only by what
 * `SERVER_SORTS` names. Where Client shows, Matter's cell leaves the client out for a screen reader.
 */
export function columnsFor(kind: ListKind, shown: string[], serverSorted: boolean): TableColumn<ListRow>[] {
  const withClient = !shown.includes('client')
  return shown.flatMap((id) => {
    const base = documentColumns[id]
    if (!base) return []
    const column: TableColumn<ListRow> = { ...base, ...widthsOf(kind, id) }
    if (serverSorted && !SERVER_SORTS.has(id)) column.allowsSorting = false
    if (id === 'matter' && !withClient) column.cell = ({ item }) => {
      const matter = matterOf(item)
      return matter && <MatterCell matter={matter} withClient={false} />
    }
    return [column]
  })
}

/** A folder's list: Title, Doc #, Version, Author and Edit Date. */
export const listColumns: TableColumn<ListRow>[] = columnsFor('folder', ['title', 'docNumber', 'version', 'author', 'edited'], true)

/** The columns a list of search hits or recent items has: the scope decides which. */
export function hitColumns(scope: SearchScope): TableColumn<ListRow>[] {
  if (scope === 'matters' || scope === 'clients') {
    return [
      { ...title, name: t('list.matter'), allowsSorting: false },
      { id: 'client', name: t('list.client'), width: '1fr', cell: ({ item }) => item.type === 'matter' && item.client.name },
    ]
  }
  if (scope === 'folders') return columnsFor('folders', ['title', 'matter'], true)
  // Title first, sharing the spare width with Matter and Author; the others are narrower here than in a folder, and each
  // ends in an ellipsis when it doesn't fit
  return columnsFor('documents', ['title', 'docNumber', 'version', 'matter', 'author', 'edited'], true)
}

/** What a list keeps while the reading pane is open and the list is narrow (spec section 6.4): Title, Doc # and Edit Date. */
export function narrowed(columns: TableColumn<ListRow>[]): TableColumn<ListRow>[] {
  return columns.filter((column) => column.id === 'title' || column.id === 'docNumber' || column.id === 'edited')
}

/**
 * Checked Out to Me: the server's order (latest check-out first), so nothing sorts, as its list takes no `onSort`. With
 * Computer and Checked Out its least widths are wider than the list beside the details anyway, so Matter keeps at least
 * its old fixed 130 there rather than give way to 100 (list columns spec 4.1).
 */
export function checkedOutColumns(): TableColumn<ListRow>[] {
  return columnsFor('checkedOut', ['title', 'docNumber', 'version', 'matter', 'computer', 'checkedOut', 'edited'], true)
}
