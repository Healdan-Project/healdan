// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useNavigate } from '@tanstack/react-router'
import { useId } from 'react'
import { ErrorPanel, useApi } from '@healdan/shell'
import { Button, Icon, SelectField, StatusDot, TextField, ToggleButtons } from '@healdan/ui'
import { routes } from '../addresses'
import { Refusal } from '../forms/Refusal'
import { useWords } from '../lists/useWords'
import { LogLines } from '../monitoring/LogLines'
import { logFileUrl, useLogs, type LogLevel } from '../monitoring/logQueries'
import { useLogLines } from '../monitoring/useLogLines'
import { StaleNote } from '../people/StaleNote'
import { typed } from '../pickers/typing'
import { t } from '../strings'
import listStyles from '../matters/Matters.module.css'
import styles from '../monitoring/Monitoring.module.css'

// The level a log's lines are kept from is the log's reads' (monitoring/logQueries.ts), named here too as the route's
export type { LogLevel }

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
 * The Server Log, at /admin/server-log (spec 8.8): the lines of the main log or a task's, as one log across the files it
 * rolled over to (P5-R27), with Everything, Warnings and Errors, or Errors, and words to search for, all kept in the
 * address. It opens on the newest lines, following live: what's new comes every 3 seconds while the page is visible, until
 * Pause (P5-R21). Show Earlier, or the box scrolled to its top, reads back. Download is the log's files, zipped. The route
 * hands in the address's parameters as `search`.
 */
export function ServerLogView({ search }: { search: ServerLogSearch }) {
  const navigate = useNavigate({ from: routes.serverLog })
  const api = useApi()
  const headingId = useId()
  const logs = useLogs()
  const level = search.level ?? 'all'
  const list = logs.data
  // The log the address names, or the first the server lists, the main log; none while the list is still to come
  const shown = list === undefined ? undefined : search.log === undefined ? list[0] : list.find((one) => one.name === search.log)
  const unknown = list !== undefined && search.log !== undefined && shown === undefined
  const lines = useLogLines(shown?.name, level, search.q)
  const filtered = level !== 'all' || search.q !== undefined

  /** The address with `change` made, kept in place of the one before: a log or a filter isn't a step of its own */
  function change(next: Partial<ServerLogSearch>) {
    void navigate({ to: routes.serverLog, search: (prev) => ({ ...prev, ...next }), replace: true })
  }

  const words = useWords(search.q ?? '', (q) => change({ q: q === '' ? undefined : q }))

  let body
  if (list === undefined) body = logs.isError ? <ErrorPanel error={logs.error} onRetry={() => void logs.refetch()} /> : <p className={styles.note}>{t('common.loading')}</p>
  else if (list.length === 0) body = <p className={styles.note}>{t('admin.serverLog.noLogs')}</p>
  else if (shown === undefined) body = null
  else if (lines.state === 'failed') body = <ErrorPanel error={lines.error} onRetry={lines.retry} />
  else body = <LogLines label={shown.label} lines={lines} filtered={filtered} />

  return (
    <div className={listStyles.view}>
      <div className={listStyles.head}>
        <h1 id={headingId} className={listStyles.title}>
          {t('admin.serverLog.title')}
        </h1>
        {shown !== undefined && (
          <div className={listStyles.actions}>
            <Button onPress={lines.following ? lines.pause : lines.follow}>{t(lines.following ? 'admin.serverLog.pause' : 'admin.serverLog.follow')}</Button>
            <a href={logFileUrl(api.url, shown.name)} download className={styles.linkButton}>
              <Icon name="download" size={14} />
              {t('admin.serverLog.download')}
            </a>
          </div>
        )}
      </div>
      <div role="group" aria-label={t('admin.serverLog.filters')} className={styles.toolbar}>
        <div className={listStyles.filter}>
          <SelectField
            label={t('admin.serverLog.log')}
            value={shown?.name ?? search.log ?? ''}
            onChange={(value) => change({ log: value === '' ? undefined : value })}
            options={(list ?? []).map((one) => ({ value: one.name, label: one.label }))}
          />
        </div>
        <div className={styles.level}>
          <span className={styles.levelLabel} aria-hidden>
            {t('admin.serverLog.level')}
          </span>
          <ToggleButtons
            label={t('admin.serverLog.level')}
            choices={logLevels.map((one) => ({ value: one, label: t(`admin.serverLog.level.${one}`) }))}
            value={level}
            onChange={(value) => {
              const next = logLevels.find((one) => one === value)
              if (next !== undefined) change({ level: next === 'all' ? undefined : next })
            }}
          />
        </div>
        <div className={`${listStyles.filter} ${listStyles.words}`}>
          <TextField label={t('admin.serverLog.text')} value={words.text} onChange={words.type} />
        </div>
        <div role="status" className={styles.follow}>
          {shown !== undefined && lines.state === 'loaded' && (
            <StatusDot status={lines.following ? 'ok' : 'off'}>{t(lines.following ? 'admin.serverLog.following' : 'admin.serverLog.paused')}</StatusDot>
          )}
        </div>
      </div>
      <StaleNote failed={logs.isError && list !== undefined} onRetry={() => void logs.refetch()} />
      {unknown && <Refusal>{t('admin.serverLog.unknownLog', { name: search.log ?? '' })}</Refusal>}
      {/* Said as each comes, there before it has words: a failed read of what's new once, however many tries follow */}
      <div role="status" className={styles.notes}>
        {lines.pollFailed && <p className={styles.note}>{t('admin.serverLog.pollFailed')}</p>}
        {lines.followLost && <p className={styles.note}>{t('admin.serverLog.followLost')}</p>}
        {lines.rolled && <p className={styles.note}>{t('admin.serverLog.rolled')}</p>}
        {lines.pausedToKeep && <p className={styles.note}>{t('admin.serverLog.pausedToKeep')}</p>}
      </div>
      {body}
    </div>
  )
}
