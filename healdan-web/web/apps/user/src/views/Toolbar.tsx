// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { ReactNode } from 'react'
import { plural } from '@healdan/ui'
import styles from './views.module.css'

/** What a list's count says: all its documents, some of them once filters leave some out, or "more than" a floor. */
export type ListCount = { documents: number; of?: number } | { atLeast: number }

export function countText(count: ListCount): string {
  if ('atLeast' in count) return plural('documentsMoreThan', count.atLeast)
  if (count.of !== undefined) return plural('documentsOf', count.of, { shown: count.documents })
  return plural('documents', count.documents)
}

export interface ToolbarProps {
  /** The toolbar's name for a screen reader, once it holds controls */
  label: string
  /** Undefined until the list has answered */
  count?: ListCount
  /** The view's controls; later tasks add them (spec section 6.1) */
  children?: ReactNode
  /** What follows the count, at the bar's right end: the Filters button */
  end?: ReactNode
}

// The bar above a list: the view's controls, then the count, at the end. The count is a status, so a screen reader
// hears it when sorting or filtering changes it.
export function Toolbar({ label, count, children, end }: ToolbarProps) {
  const status = (
    <span role="status" className={styles.count}>
      {count && countText(count)}
    </span>
  )
  if (!children && !end) return <div className={styles.toolbar}>{status}</div>
  return (
    <div role="toolbar" aria-label={label} className={styles.toolbar}>
      {children}
      {status}
      {end}
    </div>
  )
}
