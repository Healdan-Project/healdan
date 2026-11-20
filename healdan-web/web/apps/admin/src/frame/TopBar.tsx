// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { Link } from '@tanstack/react-router'
import { HelpMenu, Mark, PersonMenu } from '@healdan/shell'
import { Button, Icon } from '@healdan/ui'
import { t } from '../strings'
import { FindBox } from './FindBox'
import styles from './TopBar.module.css'

/** The Admin Guide, which Help opens */
export const ADMIN_GUIDE = 'https://docs.healdan.com/admin/the-admin-app/'

/** The pane's button: where the focus goes back to when Esc closes the pane */
export const NAVIGATION_BUTTON = 'data-navigation-button'

export interface NavigationButton {
  open: boolean
  onToggle: () => void
}

/**
 * The top bar (spec 7.1): Healdan's mark, Healdan and an Admin tag, which go to the Dashboard; the find; Open Healdan, Help
 * and the person's menu. In a narrow window it starts with the button behind which the left pane folds away.
 */
export function TopBar({ navigation }: { navigation?: NavigationButton }) {
  return (
    <header className={styles.bar}>
      {navigation && (
        <Button variant="quiet" aria-label={t('frame.navigation')} aria-expanded={navigation.open} onPress={navigation.onToggle} {...{ [NAVIGATION_BUTTON]: true }}>
          <Icon name="menu" size={18} />
        </Button>
      )}
      <Link className={styles.brand} to="/">
        <Mark />
        <span>{t('app.name')}</span> <span className={styles.tag}>{t('admin.app.tag')}</span>
      </Link>
      <FindBox />
      <div className={styles.right}>
        <a className={styles.open} href="/app/">
          <Icon name="arrowUpRight" />
          {t('admin.frame.openHealdan')}
        </a>
        <HelpMenu guide={ADMIN_GUIDE} label={t('admin.help.guide')} />
        <PersonMenu />
      </div>
    </header>
  )
}
