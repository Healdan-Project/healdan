// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { components } from '@healdan/api'
import { useSelection } from '../selection'
import { ActionButton } from './ActionControls'
import { MoreMenu } from './MoreMenu'
import { useDocumentActions } from './useDocumentActions'

/**
 * The toolbar's buttons for the selected document (spec 6.1): Open, Preview, Download and Check Out, all disabled with
 * none or several selected, then More (⋯), which acts on `selected` (the one document by default). A row's right-click
 * menu offers the same actions, from the same rules (useDocumentActions).
 */
export function SelectionActions({
  document,
  selected = document ? [document] : [],
}: {
  document: components['schemas']['DocumentView'] | undefined
  selected?: components['schemas']['DocumentView'][]
}) {
  // Preview follows the address, as Space does: its document may not be among the rows drawn yet
  const { selected: inAddress } = useSelection()
  const actions = useDocumentActions({ document, documents: selected, preview: inAddress })
  return (
    <>
      <ActionButton action={actions.open} />
      <ActionButton action={actions.preview} />
      <ActionButton action={actions.download} />
      <ActionButton action={actions.checkOut} />
      <MoreMenu actions={actions} isDisabled={selected.length === 0} />
    </>
  )
}
