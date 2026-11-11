// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { t } from '@healdan/ui'
import styles from './views.module.css'

// Task 14 replaces this with the details panel; until then the region shows which document is selected.
export function DetailsStub({ docNumber, name }: { docNumber: number; name?: string }) {
  return (
    <div className={styles.details}>
      {name && <h2 className={styles.detailsName}>{name}</h2>}
      <p className={styles.detailsMeta}>{t('details.docNumber', { n: docNumber })}</p>
    </div>
  )
}
