// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useMemo } from 'react'
import type { components } from '@healdan/api'
import { plural, t, type ItemMenu } from '@healdan/ui'
import { childKey, type Hit } from '../views/useListing'
import { DocumentMenuItems, folderMenu, MatterMenuItems } from './ContextMenus'

export interface ListMenuOf {
  /** What the list holds, as far as it's loaded */
  rows: readonly Hit[]
  /** The Table's selection, by row key */
  selection: ReadonlySet<string>
  /** Changes the selection, as a click does */
  onSelect: (keys: Set<string>) => void
  /** The selected documents, in the list's order: what the toolbar's More acts on */
  selected: components['schemas']['DocumentView'][]
}

function nameOf(hit: Hit): string {
  return hit.type === 'matter' ? hit.display : hit.name
}

/**
 * A list's right-click menu, by row (changed after M6: the owner's dogfooding punch list, 2026-11-14). A right-click on a
 * document that isn't selected selects it first, in place of the selection, as Finder does; one inside the selection keeps
 * it, and the menu then acts on all of it. A folder's or a matter's row isn't selected, and leaves the selection alone.
 */
export function useListMenu({ rows, selection, onSelect, selected }: ListMenuOf): ItemMenu {
  const byKey = useMemo(() => new Map(rows.map((row) => [childKey(row), row])), [rows])
  const documentsFor = (key: string, hit: components['schemas']['DocumentView']) => (selection.has(key) && selected.length > 0 ? selected : [hit])
  return {
    label: (key) => {
      const hit = byKey.get(key)
      if (!hit) return ''
      const documents = hit.type === 'document' ? documentsFor(key, hit) : []
      return documents.length > 1 ? plural('menuForDocuments', documents.length) : t('menu.for', { name: nameOf(hit) })
    },
    items: (key) => {
      const hit = byKey.get(key)
      if (!hit) return undefined
      if (hit.type === 'document') return <DocumentMenuItems documents={documentsFor(key, hit)} />
      if (hit.type === 'matter') return <MatterMenuItems folderId={hit.folderId} />
      return folderMenu(hit)
    },
    onOpen: (key) => {
      if (byKey.get(key)?.type === 'document' && !selection.has(key)) onSelect(new Set([key]))
    },
  }
}
