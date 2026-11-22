// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { adminPaths } from '@healdan/api'
import { typed } from '../pickers/typing'
import { t } from '../strings'
import styles from './views.module.css'

/** The entries a log's lines keep, as `GET /v1/admin/logs/{name}/lines` takes it: everything, warnings and errors, or errors */
export type LogLevel = NonNullable<NonNullable<adminPaths['/v1/admin/logs/{name}/lines']['get']['parameters']['query']>['level']>

export const logLevels = ['all', 'warn', 'error'] as const satisfies readonly LogLevel[]

/** A log's name as the contract's LogName takes it: never a path, nor dots alone */
const LOG_NAME = /^[^/\\]*[^/\\.][^/\\]*$/

/** What the Server Log's address holds: the log shown, the level its entries are kept from, and its words */
export interface ServerLogSearch {
  /** The log's name, as `GET /v1/admin/logs` gives it, such as `DMS` */
  log?: string
  level?: LogLevel
  q?: string
}

/** The address's parameters the Server Log reads; anything else, or anything that isn't what it should be, is left out */
export function serverLogSearch(search: Record<string, unknown>): ServerLogSearch {
  const q = typeof search.q === 'string' ? typed(search.q) : ''
  return {
    log: typeof search.log === 'string' && LOG_NAME.test(search.log) ? search.log : undefined,
    level: logLevels.find((one) => one === search.level),
    q: q === '' ? undefined : q,
  }
}

/**
 * The Server Log, at /admin/server-log (spec 8.8): the lines of the main log or a task's, followed live. The route hands
 * in the address's parameters as `search`.
 */
// eslint-disable-next-line @typescript-eslint/no-unused-vars -- the log's lines read it
export function ServerLogView({ search }: { search: ServerLogSearch }) {
  return (
    <div className={styles.view}>
      <h1 className={styles.title}>{t('admin.serverLog.title')}</h1>
    </div>
  )
}
