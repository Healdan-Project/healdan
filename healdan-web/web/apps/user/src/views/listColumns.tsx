// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { formatDate, formatVersion, Icon, t, type IconName, type TableColumn, type TableRow } from '@healdan/ui'
import { fileIcon } from '../fileIcon'
import { childKey, type Child } from './useListing'
import styles from './views.module.css'

export interface ListRow extends TableRow {
  item: Child
}

export function rowOf(item: Child): ListRow {
  return { id: childKey(item), textValue: item.name, selectable: item.type !== 'folder', item }
}

function iconOf(item: Child): IconName {
  if (item.type === 'folder') return item.kind === 'searchFolder' ? 'folderSearch' : 'folder'
  return fileIcon(item.name)
}

// Title shows the file name: CE has no title of its own, and the server sorts Title by it (plan correction 7).
export const listColumns: TableColumn<ListRow>[] = [
  {
    id: 'title',
    name: t('list.title'),
    isRowHeader: true,
    allowsSorting: true,
    width: '3fr',
    cell: ({ item }) => (
      <>
        <Icon name={iconOf(item)} />
        <span className={styles.name}>{item.name}</span>
      </>
    ),
  },
  { id: 'docNumber', name: t('list.docNumber'), allowsSorting: true, align: 'end', width: 96, cell: ({ item }) => item.type === 'document' && String(item.docNumber) },
  { id: 'version', name: t('list.version'), allowsSorting: true, align: 'end', width: 84, cell: ({ item }) => item.type === 'document' && formatVersion(item.fileVersion) },
  { id: 'author', name: t('list.author'), allowsSorting: true, width: '1fr', cell: ({ item }) => item.type === 'document' && item.author?.displayName },
  { id: 'edited', name: t('list.edited'), allowsSorting: true, width: 132, cell: ({ item }) => item.type === 'document' && formatDate(item.lastModified) },
]
