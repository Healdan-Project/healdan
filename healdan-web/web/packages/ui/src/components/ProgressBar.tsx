// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useId } from 'react'
import { Label, ProgressBar as AriaProgressBar } from 'react-aria-components'
import { plural } from '../strings'
import styles from './ProgressBar.module.css'

export interface ProgressBarProps {
  /** What is under way, which names the bar */
  label: string
  /** How many are done */
  value: number
  /** How many there are in all */
  total: number
  /** A line under the bar, which describes it to a screen reader too: how long is left, what goes on meanwhile */
  detail?: string
}

/**
 * How far a job has got, as React Aria's progress bar: its label, the count done of the total beside it (which a screen
 * reader hears in place of a percentage), the bar, and the detail under it.
 */
export function ProgressBar({ label, value, total, detail }: ProgressBarProps) {
  const detailId = useId()
  const count = plural('progress.count', total, { value })
  const share = total > 0 ? Math.min(100, Math.max(0, (value / total) * 100)) : 0
  return (
    <AriaProgressBar className={styles.progress} value={value} minValue={0} maxValue={total} valueLabel={count} aria-describedby={detail ? detailId : undefined}>
      <div className={styles.top}>
        <Label className={styles.label}>{label}</Label>
        <span className={styles.count}>{count}</span>
      </div>
      <div className={styles.track}>
        <div className={styles.fill} data-fill style={{ width: `${share}%` }} />
      </div>
      {detail && (
        <span id={detailId} className={styles.detail}>
          {detail}
        </span>
      )}
    </AriaProgressBar>
  )
}
