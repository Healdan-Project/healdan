// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { adminComponents } from '@healdan/api'
import type { Status } from '@healdan/ui'
import { plural, t } from '../strings'

type A = adminComponents['schemas']

/** The longest interval a LogicalDOC task's schedule takes, a year, as the server's rule has it (P5-R25) */
export const MAX_INTERVAL_SECONDS = 31_536_000

/** The units Every is typed in, by the seconds in each, largest last */
export const UNIT_SECONDS = { seconds: 1, minutes: 60, hours: 3600 } as const

export type Unit = keyof typeof UNIT_SECONDS

export const units = ['seconds', 'minutes', 'hours'] as const satisfies readonly Unit[]

/** An interval in the largest unit that fits it whole: 1,800 seconds is 30 minutes, 90 seconds stays 90 seconds */
export function intervalParts(seconds: number): { every: number; unit: Unit } {
  const unit = [...units].reverse().find((one) => seconds % UNIT_SECONDS[one] === 0) ?? 'seconds'
  return { every: seconds / UNIT_SECONDS[unit], unit }
}

/** An interval in words: "Every 30 minutes", "Every hour" */
export function intervalWords(seconds: number): string {
  const { every, unit } = intervalParts(seconds)
  return plural(`admin.jobs.every.${unit}`, every)
}

/** The most Every takes in a unit: as many of it as a year holds */
export function maxEvery(unit: Unit): number {
  return Math.floor(MAX_INTERVAL_SECONDS / UNIT_SECONDS[unit])
}

/**
 * The seconds Every says, as the server takes them, or undefined when it wouldn't: a whole number, of the unit, from one
 * second to a year (31,536,000 seconds), its spaces at either end aside
 */
export function secondsOf(every: string, unit: Unit): number | undefined {
  const typed = every.trim()
  if (!/^\d+$/.test(typed)) return undefined
  const seconds = Number(typed) * UNIT_SECONDS[unit]
  return seconds >= 1 && seconds <= MAX_INTERVAL_SECONDS ? seconds : undefined
}

/** How a job's last run ended, or that it's running, in words and a status for its dot */
export function resultOf(job: A['Job']): { status: Status; words: string } {
  if (job.running) return { status: 'ok', words: t('admin.jobs.result.running') }
  switch (job.result) {
    case 'succeeded':
      return { status: 'ok', words: t('admin.jobs.result.succeeded') }
    case 'failed':
      return { status: 'attention', words: t('admin.jobs.result.failed') }
    case 'done':
      return { status: 'ok', words: t('admin.jobs.result.done') }
    default:
      return { status: 'off', words: t('admin.jobs.result.none') }
  }
}

/** Whether it runs: Yes, No for a task disabled, and Off on This Server for one of Healdan's jobs that doesn't run here */
export function enabledWords(job: A['Job']): string {
  if (job.enabled) return t('admin.jobs.enabled.yes')
  return t(job.logicalDocTask ? 'admin.jobs.enabled.no' : 'admin.jobs.enabled.off')
}
