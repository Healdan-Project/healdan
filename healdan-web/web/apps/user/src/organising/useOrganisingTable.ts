// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useMemo } from 'react'
import type { components } from '@healdan/api'
import { toast, t } from '@healdan/ui'
import { ROWS_TYPE } from '../filing/dropTargets'
import { useListMenu, type ListMenuOf } from '../menus/useListMenu'
import type { Hit } from '../views/useListing'
import { useOrganising } from './OrganisingProvider'
import { rowsPayload } from './rows'

type Document = components['schemas']['DocumentView']

/**
 * What a list of documents does for organising (spec 6.7, 10.5): F2 renames the row, Delete asks to move the selection to
 * the trash, and dragging rows carries them to a folder of the tree. `selected` are the selected documents, for More, and
 * `contextMenu` the rows' right-click menu, which acts on them too (useListMenu). `onSelect` selects the row a right-click
 * lands on; none where the address alone selects.
 */
export function useOrganisingTable(rows: readonly Hit[], selection: ReadonlySet<string>, onSelect?: ListMenuOf['onSelect']) {
  const organising = useOrganising()
  const byKey = useMemo(() => new Map(rows.flatMap((row): [string, Document][] => (row.type === 'document' ? [[`d${row.docNumber}`, row]] : []))), [rows])
  // In the list's order, whichever was selected first
  const documentsOf = (keys: Iterable<string>) => {
    const wanted = new Set(keys)
    return [...byKey].flatMap(([key, document]) => (wanted.has(key) ? [document] : []))
  }
  const selected = documentsOf(selection)
  const contextMenu = useListMenu({ rows, selection, onSelect, selected })
  return {
    selected,
    contextMenu,
    tableProps: {
      // A row holding the rename field isn't dragged, so selecting text in the field starts no drag of the row
      undraggable: organising.renaming === undefined ? undefined : `d${organising.renaming}`,
      onRename: (key: string) => {
        const document = byKey.get(key)
        if (!document) return
        // Several selected means no one to rename; the focused row is the one when the selection is it or empty
        if (selection.size > 1) toast(t('rename.onlyOne'))
        else organising.startRename(document.docNumber)
      },
      onDelete: (keys: Set<string>) => {
        const documents = documentsOf(keys)
        if (documents.length > 0) organising.askDelete(documents)
      },
      drag: { type: ROWS_TYPE, data: (keys: string[]) => (documentsOf(keys).length > 0 ? rowsPayload(documentsOf(keys)) : undefined) },
    },
  }
}
