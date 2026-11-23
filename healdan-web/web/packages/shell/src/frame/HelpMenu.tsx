// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { Icon, Menu, MenuItem, t } from '@healdan/ui'

/**
 * Help: the app's own guide, at `guide` and named `label` (the User Guide unless an app names its own), and the
 * open-source notices, which cover every app of the workspace.
 */
export function HelpMenu({ guide, label = t('help.guide') }: { guide: string; label?: string }) {
  return (
    <Menu label={<Icon name="help" size={18} />} aria-label={t('help.menu')}>
      <MenuItem id="guide" icon="book" href={guide} target="_blank" rel="noopener noreferrer">
        {label}
      </MenuItem>
      <MenuItem id="notices" icon="document" href="/app/notices.txt" target="_blank" rel="noopener noreferrer">
        {t('help.notices')}
      </MenuItem>
    </Menu>
  )
}
