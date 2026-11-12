// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { Link } from '@tanstack/react-router'
import { Button, Icon, t } from '@healdan/ui'
import { SearchBox } from '../search/SearchBox'
import { HelpMenu } from './HelpMenu'
import { Mark } from './Mark'
import { PersonMenu } from './PersonMenu'
import { REGION_BUTTON, useRightRegion } from './RightRegion'
import styles from './TopBar.module.css'

/** The pane's button: where the focus goes back to when Esc closes the pane */
export const NAVIGATION_BUTTON = 'data-navigation-button'

export interface NavigationButton {
  open: boolean
  onToggle: () => void
}

/**
 * The top bar. In a narrow window it holds the buttons behind which the left pane and the right region fold away, each
 * named for what it opens and saying whether it is open.
 */
export function TopBar({ navigation }: { navigation?: NavigationButton }) {
  const region = useRightRegion()
  return (
    <header className={styles.bar}>
      {navigation && (
        <Button variant="quiet" aria-label={t('frame.navigation')} aria-expanded={navigation.open} onPress={navigation.onToggle} {...{ [NAVIGATION_BUTTON]: true }}>
          <Icon name="menu" size={18} />
        </Button>
      )}
      <Link className={styles.brand} to="/">
        <Mark />
        <span>{t('app.name')}</span>
      </Link>
      <SearchBox />
      <div className={styles.right}>
        <HelpMenu />
        {region.narrow && (
          <Button variant="quiet" aria-label={t('frame.side')} aria-expanded={region.narrowOpen} onPress={() => region.setNarrowOpen(!region.narrowOpen)} {...{ [REGION_BUTTON]: true }}>
            <Icon name="dock" size={18} />
          </Button>
        )}
        <PersonMenu />
      </div>
    </header>
  )
}
