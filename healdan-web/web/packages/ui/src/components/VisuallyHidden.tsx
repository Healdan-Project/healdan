// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { ReactNode } from 'react'
import styles from './VisuallyHidden.module.css'

/** Text a screen reader reads and the page doesn't draw, as a name that says more than what is drawn beside it */
export function VisuallyHidden({ children }: { children: ReactNode }) {
  return <span className={styles.hidden}>{children}</span>
}
