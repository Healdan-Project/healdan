// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { Link } from '@tanstack/react-router'
import { t } from '../strings'
import styles from './views.module.css'

/** An address under /admin that no screen has, in the frame, with the way back to the Dashboard */
export function NotFoundView() {
  return (
    <div className={styles.view}>
      <h1 className={styles.title}>{t('admin.notFound.title')}</h1>
      <p className={styles.line}>{t('admin.notFound.message')}</p>
      <Link to="/" className={styles.link}>
        {t('admin.notFound.dashboard')}
      </Link>
    </div>
  )
}
