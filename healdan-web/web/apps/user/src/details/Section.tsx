// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useId, type ReactNode } from 'react'
import styles from './details.module.css'

/** A titled part of the details panel: a group a screen reader announces by its title, not a landmark. */
export function Section({ title, children }: { title: string; children: ReactNode }) {
  const id = useId()
  return (
    <div role="group" aria-labelledby={id} className={styles.section}>
      <h3 id={id} className={styles.sectionTitle}>
        {title}
      </h3>
      {children}
    </div>
  )
}
