// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { Icon, Menu, MenuItem, t } from '@healdan/ui'

export function HelpMenu() {
  return (
    <Menu label={<Icon name="help" size={18} />} aria-label={t('help.menu')}>
      <MenuItem id="guide" href="https://docs.healdan.com/user/" target="_blank" rel="noopener noreferrer">
        {t('help.guide')}
      </MenuItem>
      <MenuItem id="notices" href="/app/notices.txt" target="_blank" rel="noopener noreferrer">
        {t('help.notices')}
      </MenuItem>
    </Menu>
  )
}
