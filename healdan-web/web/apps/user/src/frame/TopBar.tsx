// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { Link } from '@tanstack/react-router'
import { t } from '@healdan/ui'
import { SearchBox } from '../search/SearchBox'
import { HelpMenu } from './HelpMenu'
import { Mark } from './Mark'
import { PersonMenu } from './PersonMenu'
import styles from './TopBar.module.css'

export function TopBar() {
  return (
    <header className={styles.bar}>
      <Link className={styles.brand} to="/">
        <Mark />
        <span>{t('app.name')}</span>
      </Link>
      <SearchBox />
      <div className={styles.right}>
        <HelpMenu />
        <PersonMenu />
      </div>
    </header>
  )
}
