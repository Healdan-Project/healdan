// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { t } from '@healdan/ui'
import styles from './views.module.css'

// Unreadable and missing read the same (M2): the app never says which.
export function NotFound() {
  return (
    <section className={styles.view}>
      <p>{t('notFound.message')}</p>
    </section>
  )
}
