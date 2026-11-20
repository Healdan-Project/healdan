// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { ReactNode } from 'react'
import styles from './StatusDot.module.css'

/** Working as it should; something wants a look; switched off or not in use */
export type Status = 'ok' | 'attention' | 'off'

export interface StatusDotProps {
  status: Status
  /** The words that say the status, which are required: the dot's colour alone never says it */
  children: ReactNode
}

/** A status in words, with a dot in its colour before them as decoration */
export function StatusDot({ status, children }: StatusDotProps) {
  return (
    <span className={styles.status} data-status={status}>
      <span className={styles.dot} data-dot aria-hidden />
      <span>{children}</span>
    </span>
  )
}
