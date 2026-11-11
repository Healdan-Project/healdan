// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { components } from '@healdan/api'
import { Icon, Menu, MenuItem, t } from '@healdan/ui'
import { useOrganising } from '../organising/OrganisingProvider'
import { copyLink } from './copyLink'

/**
 * The toolbar's More (spec 6.1): Rename and Copy Link for one document, Move To… and Delete for any number of them.
 * Rename edits the title in place; Move To… and Delete ask first.
 */
export function MoreMenu({ documents }: { documents: components['schemas']['DocumentView'][] }) {
  const organising = useOrganising()
  const one = documents.length === 1 ? documents[0] : undefined
  return (
    <Menu label={<Icon name="dots" size={14} />} aria-label={t('organising.more')} isDisabled={documents.length === 0}>
      <MenuItem id="rename" isDisabled={!one} onAction={() => one && organising.startRename(one.docNumber)}>
        {t('rename.menu')}
      </MenuItem>
      <MenuItem id="move" onAction={() => organising.askMove(documents)}>
        {t('move.menu')}
      </MenuItem>
      <MenuItem id="delete" onAction={() => organising.askDelete(documents)}>
        {t('delete.menu')}
      </MenuItem>
      <MenuItem id="copyLink" isDisabled={!one} onAction={() => one && void copyLink(one.docNumber)}>
        {t('details.copyLink')}
      </MenuItem>
    </Menu>
  )
}
