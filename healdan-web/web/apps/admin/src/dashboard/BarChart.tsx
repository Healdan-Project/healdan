// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { formatDay } from '@healdan/ui'
import { t } from '../strings'
import styles from './BarChart.module.css'

export interface Day {
  /** The day, 2026-11-19 */
  date: string
  count: number
}

// The drawing's own units: the bars share its width, and it stretches to the card's
const WIDTH = 300
const HEIGHT = 84
const GAP = 0.25

/** "Nov 19", the first day under the bars */
function shortDay(day: string): string {
  const parts = /^(\d{4})-(\d{2})-(\d{2})$/.exec(day)
  if (!parts) return ''
  const date = new Date(Number(parts[1]), Number(parts[2]) - 1, Number(parts[3]))
  return new Intl.DateTimeFormat(undefined, { month: 'short', day: 'numeric' }).format(date)
}

/**
 * Counts by day, oldest first and today last, as plain SVG bars (spec 8.1; no chart library). The drawing is an image
 * named `label`, and its numbers are in a table beside it that a screen reader reads and the page doesn't show, so
 * nothing is said by the bars alone. The tallest bar is the chart's height; a day with none has no bar.
 */
export function BarChart({ label, days }: { label: string; days: readonly Day[] }) {
  const most = Math.max(0, ...days.map((day) => day.count))
  const slot = days.length > 0 ? WIDTH / days.length : WIDTH
  return (
    <figure className={styles.chart}>
      <svg role="img" aria-label={label} viewBox={`0 0 ${WIDTH} ${HEIGHT}`} preserveAspectRatio="none" className={styles.drawing}>
        {days.map((day, index) => {
          const height = most > 0 ? (day.count / most) * HEIGHT : 0
          return <rect key={day.date} data-bar className={styles.bar} x={index * slot + (slot * GAP) / 2} y={HEIGHT - height} width={slot * (1 - GAP)} height={height} />
        })}
        <line className={styles.baseline} x1={0} y1={HEIGHT} x2={WIDTH} y2={HEIGHT} vectorEffect="non-scaling-stroke" />
      </svg>
      {days.length > 0 && (
        <div className={styles.axis} aria-hidden="true" data-axis>
          <span>{shortDay(days[0]!.date)}</span>
          <span>{t('admin.usage.today')}</span>
        </div>
      )}
      <div className={styles.hidden} data-visually-hidden>
        <table>
          <caption>{label}</caption>
          <thead>
            <tr>
              <th scope="col">{t('admin.usage.day')}</th>
              <th scope="col">{t('admin.usage.documents')}</th>
            </tr>
          </thead>
          <tbody>
            {days.map((day) => (
              <tr key={day.date}>
                <th scope="row">{formatDay(day.date)}</th>
                <td>{day.count}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </figure>
  )
}
