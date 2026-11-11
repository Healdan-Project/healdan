// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { components } from '@healdan/api'
import { formatDate, formatVersion, Icon, t, type IconName, type TableColumn, type TableRow } from '@healdan/ui'
import type { SearchScope } from '../search/scopes'
import { fileIcon } from '../fileIcon'
import { matterParts } from '../matterDisplay'
import { childKey, type Hit } from './useListing'
import styles from './views.module.css'

type S = components['schemas']

export interface ListRow extends TableRow {
  item: Hit
}

export function rowOf(item: Hit): ListRow {
  return { id: childKey(item), textValue: item.type === 'matter' ? item.display : item.name, selectable: item.type === 'document', item }
}

function iconOf(item: Hit): IconName {
  if (item.type === 'matter') return 'briefcase'
  if (item.type === 'folder') return item.kind === 'searchFolder' ? 'folderSearch' : 'folder'
  return fileIcon(item.name)
}

// Title shows the file name: CE has no title of its own, and the server sorts Title by it (plan correction 7).
const title: TableColumn<ListRow> = {
  id: 'title',
  name: t('list.title'),
  isRowHeader: true,
  allowsSorting: true,
  width: '3fr',
  cell: ({ item }) => (
    <>
      <Icon name={iconOf(item)} />
      <span className={styles.name}>{item.type === 'matter' ? item.display : item.name}</span>
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
 * "1001.1", the client muted after it, and the matter's name as a tooltip. The name is text in the cell too: a screen
 * reader reads it, and a row that is hovered or has the keyboard's focus shows it in place of the client (views.module.css),
 * as a tooltip alone can't be reached by keyboard.
 */
function MatterCell({ matter }: { matter: S['MatterRef'] }) {
  const { number, name } = matterParts(matter.display)
  return (
    <span className={styles.matterCell} title={name}>
      {number} <span className={styles.matterClient}>{matter.client.name}</span>
      {name && <span className={styles.matterName}>{name}</span>}
    </span>
  )
}

function matterOf(item: Hit): S['MatterRef'] | null {
  return item.type === 'matter' ? null : item.matter
}

// Built when a list draws, so the firm's own word for a matter (setLabels) is the heading
function matterColumn(): TableColumn<ListRow> {
  return { id: 'matter', name: t('list.matter'), width: 150, cell: ({ item }) => {
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
  if (scope === 'folders') return [title, matterColumn()]
  return [title, docNumber, version, matterColumn(), author, edited]
}

/** What a list keeps while the reading pane is open and the list is narrow (spec section 6.4): Title, Doc # and Edit Date. */
export function narrowed(columns: TableColumn<ListRow>[]): TableColumn<ListRow>[] {
  return columns.filter((column) => column.id === 'title' || column.id === 'docNumber' || column.id === 'edited')
}
