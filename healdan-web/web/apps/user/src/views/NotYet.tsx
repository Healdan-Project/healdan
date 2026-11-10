// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { t } from '@healdan/ui'
import styles from './views.module.css'

// An address a later task fills in.
export function NotYet({ title }: { title: string }) {
  return (
    <section className={styles.view}>
      <h1 className={styles.title}>{title}</h1>
      <p>{t('notYet.message')}</p>
    </section>
  )
}
