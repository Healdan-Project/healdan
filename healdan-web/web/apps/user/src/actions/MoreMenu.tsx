// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { Icon, Menu, t } from '@healdan/ui'
import { ActionItem } from './ActionControls'
import type { DocumentActions } from './useDocumentActions'

/**
 * The toolbar's More (spec 6.1): Rename and Copy Link for one document, Move To… and Delete for any number of them.
 * Rename edits the title in place; Move To… and Delete ask first. Disabled with nothing selected.
 */
export function MoreMenu({ actions, isDisabled }: { actions: DocumentActions; isDisabled: boolean }) {
  return (
    <Menu label={<Icon name="dots" size={14} />} aria-label={t('organising.more')} isDisabled={isDisabled}>
      <ActionItem action={actions.rename} />
      <ActionItem action={actions.move} />
      <ActionItem action={actions.delete} />
      <ActionItem action={actions.copyLink} />
    </Menu>
  )
}
