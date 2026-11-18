// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useMemo } from 'react'
import type { components } from '@healdan/api'
import { plural, t, type ItemMenu } from '@healdan/ui'
import type { MovingFolder } from '../actions/folders'
import { childKey, selectable, type Hit } from '../views/useListing'
import { DocumentMenuItems, folderMenu, MatterMenuItems, SelectionMenuItems } from './ContextMenus'

export interface ListMenuOf {
  /** What the list holds, as far as it's loaded */
  rows: readonly Hit[]
  /** The Table's selection, by row key */
  selection: ReadonlySet<string>
  /** Changes the selection for a right-click, which leaves Filters open (`showDetails: false`); none where the address selects */
  onSelect?: (keys: Set<string>, options: { showDetails: boolean }) => void
  /** The selected documents, in the list's order: what the toolbar's More acts on */
  selected: components['schemas']['DocumentView'][]
  /** The selected folders, in the list's order: a selection of several with one of them in it is a selection's, not a row's */
  folders: readonly MovingFolder[]
}

function nameOf(hit: Hit): string {
  return hit.type === 'matter' ? hit.display : hit.name
}

/**
 * A list's right-click menu, by row (changed after M6: the owner's dogfooding punch list, 2026-11-14). A right-click on a
 * document or a folder that isn't selected selects it first, in place of the selection, as Finder does; one inside the
 * selection keeps it, and the menu then acts on all of it: several documents have a document's menu with only what takes
 * several, and a selection with a folder in it has Move To… and Delete (folders and Trash spec 7.5). A matter's row, or a
 * matter's own folder's, isn't selected, and leaves the selection alone. The right-click doesn't show the row's details in place of Filters, as a click
 * does: it only asked for a menu.
 */
export function useListMenu({ rows, selection, onSelect, selected, folders }: ListMenuOf): ItemMenu {
  const byKey = useMemo(() => new Map(rows.map((row) => [childKey(row), row])), [rows])
  const several = (key: string) => selection.has(key) && selection.size > 1
  const documentsFor = (key: string, hit: components['schemas']['DocumentView']) => (selection.has(key) && selected.length > 0 ? selected : [hit])
  const mixed = (key: string) => several(key) && folders.length > 0
  return {
    label: (key) => {
      const hit = byKey.get(key)
      if (!hit) return ''
      if (mixed(key)) return plural('menuForItems', selected.length + folders.length)
      const documents = hit.type === 'document' ? documentsFor(key, hit) : []
      return documents.length > 1 ? plural('menuForDocuments', documents.length) : t('menu.for', { name: nameOf(hit) })
    },
    items: (key) => {
      const hit = byKey.get(key)
      if (!hit) return undefined
      if (mixed(key)) return <SelectionMenuItems items={{ documents: selected, folders }} />
      if (hit.type === 'document') return <DocumentMenuItems documents={documentsFor(key, hit)} />
      if (hit.type === 'matter') return <MatterMenuItems folderId={hit.folderId} />
      return folderMenu(hit, 'row')
    },
    onOpen: (key) => {
      const hit = byKey.get(key)
      if (hit && selectable(hit) && !selection.has(key)) onSelect?.(new Set([key]), { showDetails: false })
    },
  }
}
