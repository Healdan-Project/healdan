// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { components } from '@healdan/api'
import { formatDate, formatDateTime, formatSize, formatVersion, Icon, t, VisuallyHidden, type TableColumn, type TableRow } from '@healdan/ui'
import { computerLabel } from '../actions/checkout'
import { HoldMarker } from '../actions/HoldMarker'
import type { OtherScope } from '../search/scopes'
import { typeLabel } from '../filters/FiltersPanel'
import { itemIcon } from '../kindIcons'
import { DocumentTitle, FolderName } from '../organising/RenameField'
import type { DocumentListKind, ListKind } from './columnLayouts'
import { childKey, selectable, sortColumns, type Hit } from './useListing'
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
  // Wide enough for a date and a time with the cell's padding: US English's widest, "May 30, 2026, 10:48 AM", is 148 pixels
  // in macOS's system font at the list's 13 pixels, so it needs 173, and 176 leaves a margin
  lastUsed: { id: 'lastUsed', name: t('list.lastUsed'), allowsSorting: true, newestFirst: true, width: 176, cell: ({ item }) => item.type !== 'matter' && item.lastUsed ? formatDateTime(item.lastUsed) : null },
  computer: {
    id: 'computer',
    name: t('list.computer'),
    allowsSorting: true,
    width: 130,
    cell: ({ item }) => item.type === 'document' && <ComputerCell hold={item.hold} />,
  },
  checkedOut: { id: 'checkedOut', name: t('list.checkedOut'), allowsSorting: true, newestFirst: true, width: 116, cell: ({ item }) => item.type === 'document' && item.hold?.since ? formatDate(item.hold.since) : null },
}

// Spec 4.7's table order: the columns every list offers, then Last Used (Recent) or Computer and Checked Out (Checked Out to Me)
const OFFERED_ORDER = ['title', 'docNumber', 'version', 'author', 'edited', 'matter', 'client', 'location', 'filed', 'size', 'type', 'lastUsed', 'computer', 'checkedOut']
/** The columns only some lists offer, and those lists; every other column of OFFERED_ORDER is every list's */
const OFFERED_BY: Record<string, readonly DocumentListKind[]> = {
  lastUsed: ['recentDocuments', 'recentEmails'],
  computer: ['checkedOut'],
  checkedOut: ['checkedOut'],
}

/**
 * The columns a list of documents offers, in spec 4.7's order. The kind is the list's own, never the layout beside the
 * reading pane, which only picks among them: `beside` itself offers only the columns every list offers.
 */
export function offeredFor(kind: DocumentListKind): string[] {
  return OFFERED_ORDER.filter((id) => !Object.hasOwn(OFFERED_BY, id) || OFFERED_BY[id]!.includes(kind))
}

/**
 * What the server can sort a paged list by yet (spec 6), the columns a folder's or a search's sort takes (`sortColumns`); the
 * other columns sort only a list the browser holds whole
 */
export const SERVER_SORTS: ReadonlySet<string> = new Set(sortColumns)

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

/**
 * The columns of a search's Folders, Matters or Clients scope, which aren't chosen (spec 4.8): only their widths are kept.
 * A document scope's are chosen, as every list of documents' are (`useDocumentListColumns`).
 */
export function hitColumns(scope: OtherScope): TableColumn<ListRow>[] {
  if (scope === 'folders') return columnsFor('folders', ['title', 'matter'], true)
  // A Client column of their own: the documents' Client shows the client of the matter a document or folder is in, and
  // leaves a matter's own row blank, where this one shows the matter's client
  return [
    { ...title, name: t('list.matter'), allowsSorting: false },
    { id: 'client', name: t('list.client'), width: '1fr', cell: ({ item }) => item.type === 'matter' && item.client.name },
  ]
}
