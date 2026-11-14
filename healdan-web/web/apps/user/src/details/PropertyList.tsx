// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { ReactNode } from 'react'
import { t } from '@healdan/ui'
import styles from './details.module.css'

export interface PropertyRow {
  label: string
  value: ReactNode
}

/**
 * Labelled values as the details show them, the properties and a hold's Check-Out Info alike: a label and its value on
 * each row, and None for an empty value. `wideLabels` gives the labels the room Check-Out Time needs.
 */
export function PropertyList({ rows, wideLabels = false }: { rows: PropertyRow[]; wideLabels?: boolean }) {
  return (
    <dl className={wideLabels ? `${styles.properties} ${styles.wideLabels}` : styles.properties}>
      {rows.map((row, i) => (
        <div key={i} className={styles.property}>
          <dt>{row.label}</dt>
          <dd>{row.value || t('details.none')}</dd>
        </div>
      ))}
    </dl>
  )
}
