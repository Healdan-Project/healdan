// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useMemo } from 'react'
import type { components } from '@healdan/api'
import { toast, t } from '@healdan/ui'
import type { OrganisingItems } from '../actions/batch'
import { anyDeletable, movingFolder, whyNotDelete, type MovingFolder } from '../actions/folders'
import { whyNotRename } from '../actions/rename'
import { ROWS_TYPE } from '../filing/dropTargets'
import { useListMenu, type ListMenuOf } from '../menus/useListMenu'
import { useMe } from '../queries'
import { childKey, type Hit } from '../views/useListing'
import { useOrganising } from './OrganisingProvider'
import { rowsPayload } from './rows'

type Document = components['schemas']['DocumentView']
type Folder = components['schemas']['FolderView']

/**
 * What a list does for organising (spec 6.7, 10.5; folder rename spec 5.1; folders and Trash spec 7.5): F2 renames the
 * row, a document or a folder, and on a matter's row says why it isn't renamed here; Delete asks to move the selected
 * folders and documents to the Trash, and on a matter's row, or folders none of which can be deleted, says why not; and dragging
 * rows carries them to a folder of the tree. `selected` are the selected documents and `folders` the selected folders, for
 * More, and `contextMenu` the rows' right-click menu, which acts on them too (useListMenu). `onSelect` selects the row a
 * right-click lands on; none where the address alone selects.
 */
export function useOrganisingTable(rows: readonly Hit[], selection: ReadonlySet<string>, onSelect?: ListMenuOf['onSelect']) {
  const organising = useOrganising()
  const me = useMe()
  const byKey = useMemo(() => new Map(rows.flatMap((row): [string, Document][] => (row.type === 'document' ? [[`d${row.docNumber}`, row]] : []))), [rows])
  const folders = useMemo(() => new Map(rows.flatMap((row): [string, Folder][] => (row.type === 'folder' ? [[`f${row.id}`, row]] : []))), [rows])
  const matters = useMemo(() => new Set(rows.flatMap((row) => (row.type === 'matter' ? [childKey(row)] : []))), [rows])
  // In the list's order, whichever was selected first
  const documentsOf = (keys: Iterable<string>) => {
    const wanted = new Set(keys)
    return [...byKey].flatMap(([key, document]) => (wanted.has(key) ? [document] : []))
  }
  const foldersOf = (keys: Iterable<string>): Folder[] => {
    const wanted = new Set(keys)
    return [...folders].flatMap(([key, folder]) => (wanted.has(key) ? [folder] : []))
  }
  const itemsOf = (keys: Iterable<string>): OrganisingItems => ({ documents: documentsOf(keys), folders: foldersOf(keys).map(movingFolder) })
  const selected = documentsOf(selection)
  const selectedFolders: MovingFolder[] = foldersOf(selection).map(movingFolder)
  const contextMenu = useListMenu({ rows, selection, onSelect, selected, folders: selectedFolders })
  return {
    selected,
    folders: selectedFolders,
    contextMenu,
    tableProps: {
      // A row holding the rename field isn't dragged, so selecting text in the field starts no drag of the row
      undraggable: organising.renaming?.place === 'row' ? organising.renaming.key : undefined,
      onRename: (key: string) => {
        // A matter's own folder takes the matter's name, as F2 on a matter in the tree says (folder rename spec 5.2)
        if (matters.has(key)) return toast(t('rename.matterFolder'))
        const folder = folders.get(key)
        if (folder) {
          // The focused folder is renamed, whatever else is selected
          const why = whyNotRename(folder, me.data?.admin)
          if (why) toast(why)
          else organising.startRename(key)
          return
        }
        const document = byKey.get(key)
        if (!document) return
        // Several selected means no one to rename; the focused row is the one when the selection is it or empty
        if (selection.size > 1) toast(t('rename.onlyOne'))
        else organising.startRename(key)
      },
      onDelete: (keys: Set<string>) => {
        // A matter's own folder is deleted in the Classic Interface, as Delete on a matter in the tree says
        if ([...keys].some((key) => matters.has(key))) return toast(t('delete.matterFolder'))
        const items = itemsOf(keys)
        // With nothing that can be deleted, the first folder says why, as F2 does, and nothing is asked; with several,
        // naming it. Several with something deletable are asked about, and those that can't be say why in Refusals
        if (!anyDeletable(items)) {
          const blocked = foldersOf(keys).find((folder) => !folder.canDelete)
          const why = blocked && whyNotDelete(blocked, me.data?.admin)
          if (blocked && why) toast(keys.size > 1 ? t('organising.refused', { name: blocked.name, reason: t(why) }) : t(why))
          return
        }
        organising.askDelete(items)
      },
      drag: {
        type: ROWS_TYPE,
        data: (keys: string[]) => {
          const items = itemsOf(keys)
          return items.documents.length + items.folders.length > 0 ? rowsPayload(items.documents, items.folders) : undefined
        },
      },
    },
  }
}
