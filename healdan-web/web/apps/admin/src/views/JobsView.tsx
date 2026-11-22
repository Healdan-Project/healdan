// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQueryClient } from '@tanstack/react-query'
import { useId, useRef, useState } from 'react'
import type { adminComponents } from '@healdan/api'
import { ErrorPanel, useApi } from '@healdan/shell'
import { Button, formatDateTime, StatusDot, toast, VisuallyHidden } from '@healdan/ui'
import { Confirm } from '../dialogs/Confirm'
import { Refusal } from '../forms/Refusal'
import { jobAction, jobChanged, useJobs, type JobAction } from '../monitoring/jobQueries'
import { enabledWords, intervalWords, resultOf } from '../monitoring/jobText'
import { ScheduleDialog } from '../monitoring/ScheduleDialog'
import { useKeptFocus } from '../monitoring/useKeptFocus'
import { StaleNote } from '../people/StaleNote'
import { t } from '../strings'
import type { AdminStringKey } from '../strings/en-US'
import styles from '../monitoring/Jobs.module.css'
import viewStyles from './views.module.css'

type A = adminComponents['schemas']

/** The table's columns: the job's name, Last Run, Result, Next Run, Enabled, Schedule and the row's buttons */
const COLUMNS = 7

/** Words for each action: the button's, its name with the job's, and what's said once it's done */
const WORDS: Record<JobAction, { label: AdminStringKey; name: AdminStringKey; done: AdminStringKey }> = {
  run: { label: 'admin.jobs.run', name: 'admin.jobs.runName', done: 'admin.jobs.runAsked' },
  stop: { label: 'admin.jobs.stop', name: 'admin.jobs.stopName', done: 'admin.jobs.stopAsked' },
  enable: { label: 'admin.jobs.enable', name: 'admin.jobs.enableName', done: 'admin.jobs.enabledDone' },
  disable: { label: 'admin.jobs.disable', name: 'admin.jobs.disableName', done: 'admin.jobs.disabledDone' },
}

/** A time the server gave, as the app's lists show times, with the date and time in full; `none` when there's none */
function When({ at, none }: { at: string | null; none: string }) {
  return at === null ? <span className={styles.muted}>{none}</span> : <time dateTime={at}>{formatDateTime(at)}</time>
}

/** A LogicalDOC task's schedule in words: every so many seconds, minutes or hours, or its cron expression as it is */
function Schedule({ schedule }: { schedule: A['JobSchedule'] | null }) {
  if (schedule === null) return null
  if (schedule.mode === 'cron') {
    return (
      <>
        {t('admin.jobs.cron')} <code className={styles.cron}>{schedule.cron ?? ''}</code>
      </>
    )
  }
  return schedule.intervalSeconds === null ? null : intervalWords(schedule.intervalSeconds)
}

interface JobRowProps {
  job: A['Job']
  /** Runs an action and says it's done; throws its refusal */
  act: (job: A['Job'], action: JobAction) => Promise<void>
  /** Stop or Disable, which ask first */
  ask: (job: A['Job'], action: 'stop' | 'disable') => void
  schedule: (job: A['Job']) => void
}

/**
 * A job's row, and under it, when there's anything to say, its last error and what the server refused of a button
 * pressed in it. Each button is offered only while the job allows it (the contract's flags): Run Now while it can run, in
 * whose place Stop comes while it can be stopped; Enable or Disable for a task that can be switched, whichever applies;
 * Schedule… for a task with a schedule. Run Now and Enable go at once, holding their button while on their way; Stop and
 * Disable ask first. Enable and Disable are one button whose words follow the task, so the focus stays on it.
 */
function JobRow({ job, act, ask, schedule }: JobRowProps) {
  const nameId = useId()
  const [busy, setBusy] = useState<JobAction>()
  const [failure, setFailure] = useState<unknown>()
  const result = resultOf(job)

  async function press(action: JobAction) {
    setBusy(action)
    setFailure(undefined)
    try {
      await act(job, action)
    } catch (error) {
      setFailure(error)
    } finally {
      setBusy(undefined)
    }
  }

  // `key` says which button is the same one as its words change; `data-follows`, which one's place it takes for the focus
  function button(action: JobAction, key: string, onPress: () => void, follows?: JobAction) {
    const words = WORDS[action]
    return (
      <Button key={key} aria-label={t(words.name, { name: job.label })} isPending={busy === action} onPress={onPress} data-action={action} data-follows={follows}>
        {t(words.label)}
      </Button>
    )
  }

  // Run Now and Stop are buttons of their own, so one a new read swaps for the other under the focus doesn't do the other's
  // work at the next press; Stop takes the focus from a Run Now that it follows
  let run
  if (job.canStop) run = button('stop', 'stop', () => ask(job, 'stop'), 'run')
  else if (job.canRun) run = button('run', 'run', () => void press('run'))
  let toggle
  if (job.switchable) toggle = job.enabled ? button('disable', 'toggle', () => ask(job, 'disable')) : button('enable', 'toggle', () => void press('enable'))
  const failed = job.error !== null

  return (
    <>
      <tr data-job={job.name}>
        <th scope="row" id={nameId} tabIndex={-1} className={styles.name}>
          {job.label}
        </th>
        <td>
          <When at={job.lastStart} none={t('admin.jobs.never')} />
        </td>
        <td>
          <StatusDot status={result.status}>{result.words}</StatusDot>
        </td>
        <td>
          <When at={job.nextRun} none={t('admin.jobs.noNextRun')} />
          {job.overdue && (
            <>
              {' '}
              <span className={styles.overdue}>{t('admin.jobs.overdue')}</span>
            </>
          )}
        </td>
        <td>{enabledWords(job)}</td>
        <td>
          <Schedule schedule={job.schedule} />
        </td>
        <td>
          <div className={styles.actions}>
            {run}
            {toggle}
            {job.schedule !== null && (
              <Button aria-label={t('admin.jobs.scheduleName', { name: job.label })} onPress={() => schedule(job)}>
                {t('admin.jobs.schedule')}
              </Button>
            )}
          </div>
        </td>
      </tr>
      {(failed || failure !== undefined) && (
        <tr className={styles.more}>
          <td colSpan={COLUMNS} headers={nameId}>
            {failed && (
              <p>
                <span className={styles.errorLabel}>{t('admin.jobs.lastError')}</span>
                {/* What the server logged, drawn as text */}
                <span className={styles.error}>{job.error}</span>
              </p>
            )}
            <Refusal failure={failure} />
          </td>
        </tr>
      )}
    </>
  )
}

/** Healdan's jobs, or LogicalDOC's tasks, under their heading */
function Group({ title, jobs, ...row }: { title: string; jobs: A['Job'][] } & Omit<JobRowProps, 'job'>) {
  if (jobs.length === 0) return null
  return (
    <tbody>
      <tr>
        <th scope="rowgroup" colSpan={COLUMNS} className={styles.group}>
          {title}
        </th>
      </tr>
      {jobs.map((job) => (
        <JobRow key={job.name} job={job} {...row} />
      ))}
    </tbody>
  )
}

/**
 * Background Jobs, at /admin/jobs (spec 8.8, 9.5): every job in one list, Healdan's then LogicalDOC's tasks, each with its
 * Last Run, Result, Next Run, Enabled and, for a task, its schedule in words, and only the actions it allows now. As each
 * row holds its own buttons, the list is a plain table, not the app's virtualised one, whose rows are one control each;
 * a dozen or so jobs need no paging. A failed run's error is under its row, as text. Stop and Disable ask first, naming
 * the job (spec 7.2); each action's answer is the job, which the list then shows, and the Dashboard's answer, whose Needs
 * Attention and the left pane's count count failed and overdue jobs, is asked again (P5-R20). While a job runs the list
 * asks again every 30 seconds while the page is visible, and shortly after a Run Now or a Stop (P5-R21); a return to the
 * window asks again too. While a new read fails, the list it had stays, with a note (P4-R15).
 */
export function JobsView() {
  const api = useApi()
  const queryClient = useQueryClient()
  const headingId = useId()
  const heading = useRef<HTMLHeadingElement>(null)
  const list = useRef<HTMLTableElement>(null)
  // When a Run Now or a Stop was answered: the list asks again soon after, until an answer comes that's newer
  const [askedAt, setAskedAt] = useState<number>()
  const [asking, setAsking] = useState<{ job: A['Job']; action: 'stop' | 'disable' }>()
  const [scheduling, setScheduling] = useState<A['Job']>()
  const jobs = useJobs(askedAt)
  useKeptFocus(list, heading)

  async function act(job: A['Job'], action: JobAction) {
    await jobChanged(queryClient, await jobAction(api, job.name, action))
    toast(t(WORDS[action].done, { name: job.label }))
    if (action === 'run' || action === 'stop') setAskedAt(Date.now())
  }

  const rows = { act, ask: (job: A['Job'], action: 'stop' | 'disable') => setAsking({ job, action }), schedule: setScheduling }
  let body
  if (jobs.data === undefined) {
    body = jobs.isError ? <ErrorPanel error={jobs.error} onRetry={() => void jobs.refetch()} /> : <p className={styles.muted}>{t('common.loading')}</p>
  } else if (jobs.data.length === 0) {
    body = <p className={styles.muted}>{t('admin.jobs.none')}</p>
  } else {
    body = (
      <div className={styles.tableBox}>
        <table ref={list} className={styles.table} aria-labelledby={headingId}>
          <thead>
            <tr>
              <th scope="col">{t('admin.jobs.column.name')}</th>
              <th scope="col">{t('admin.jobs.column.lastRun')}</th>
              <th scope="col">{t('admin.jobs.column.result')}</th>
              <th scope="col">{t('admin.jobs.column.nextRun')}</th>
              <th scope="col">{t('admin.jobs.column.enabled')}</th>
              <th scope="col">{t('admin.jobs.column.schedule')}</th>
              <th scope="col">
                <VisuallyHidden>{t('admin.jobs.column.actions')}</VisuallyHidden>
              </th>
            </tr>
          </thead>
          <Group title={t('admin.jobs.healdan')} jobs={jobs.data.filter((job) => !job.logicalDocTask)} {...rows} />
          <Group title={t('admin.jobs.logicalDoc')} jobs={jobs.data.filter((job) => job.logicalDocTask)} {...rows} />
        </table>
      </div>
    )
  }

  return (
    <div className={viewStyles.view}>
      <h1 id={headingId} ref={heading} tabIndex={-1} className={viewStyles.title}>
        {t('admin.jobs.title')}
      </h1>
      <p className={styles.about}>{t('admin.jobs.about')}</p>
      <StaleNote failed={jobs.isError && jobs.data !== undefined} onRetry={() => void jobs.refetch()} />
      {body}
      {asking !== undefined && (
        <Confirm
          title={t(asking.action === 'stop' ? 'admin.jobs.stopTitle' : 'admin.jobs.disableTitle', { name: asking.job.label })}
          confirm={t(asking.action === 'stop' ? 'admin.jobs.stop' : 'admin.jobs.disable')}
          run={() => act(asking.job, asking.action)}
          onClose={() => setAsking(undefined)}
        >
          <p className={styles.line}>{t(asking.action === 'stop' ? 'admin.jobs.stopLine' : 'admin.jobs.disableLine')}</p>
        </Confirm>
      )}
      {scheduling !== undefined && <ScheduleDialog job={scheduling} onClose={() => setScheduling(undefined)} />}
    </div>
  )
}
