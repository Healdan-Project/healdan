// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { ApiError, type adminComponents } from '@healdan/api'
import { useApi } from '@healdan/shell'
import { Button, Dialog, formatCount, RadioChoices, SelectField, TextField, toast } from '@healdan/ui'
import { Refusal } from '../forms/Refusal'
import { t } from '../strings'
import { useRefusalFor } from '../strings/refusals'
import formStyles from '../forms/Form.module.css'
import { jobChanged, saveSchedule } from './jobQueries'
import { intervalParts, maxEvery, secondsOf, units, type Unit } from './jobText'
import styles from './Jobs.module.css'

type A = adminComponents['schemas']
type Mode = A['JobSchedule']['mode']

/** What the dialog opens on: the task's schedule, the other mode's fields empty */
function startOf(schedule: A['JobSchedule'] | null) {
  const parts = schedule?.mode === 'simple' && schedule.intervalSeconds !== null ? intervalParts(schedule.intervalSeconds) : undefined
  return {
    mode: schedule?.mode ?? 'simple',
    every: parts === undefined ? '' : String(parts.every),
    unit: parts?.unit ?? ('minutes' as Unit),
    cron: schedule?.mode === 'cron' ? (schedule.cron ?? '') : '',
  }
}

/** The codes of the fields a `422 validation` names, by field: the first for each */
function fieldCodes(failure: unknown): Map<string, string> {
  const codes = new Map<string, string>()
  if (!(failure instanceof ApiError) || failure.code !== 'validation') return codes
  const fields = (failure.body as { fields?: unknown } | null)?.fields
  if (!Array.isArray(fields)) return codes
  for (const item of fields as { field?: unknown; code?: unknown }[]) {
    if (typeof item?.field === 'string' && !codes.has(item.field)) codes.set(item.field, String(item.code))
  }
  return codes
}

/** Whether the change is the schedule the task has, as the server judges it: the same mode, and the same seconds or expression */
function sameAs(change: A['JobScheduleChange'], schedule: A['JobSchedule'] | null): boolean {
  if (schedule === null || change.mode !== schedule.mode) return false
  return change.mode === 'simple' ? change.intervalSeconds === schedule.intervalSeconds : change.cron === (schedule.cron ?? '').trim()
}

export interface ScheduleDialogProps {
  /** A LogicalDOC task, as the list had it when Schedule… was pressed */
  job: A['Job']
  /** The dialog closes: Cancel, Esc, a click outside, or once saved. While the save is on its way it can't be. */
  onClose: () => void
}

/**
 * Schedule… (spec 8.8; P5-R18), for one of LogicalDOC's tasks: Every, a whole number of seconds, minutes or hours, or a
 * cron expression in Quartz's form, opening on the task's schedule. What the server would refuse is said before Save, by
 * the server's own rule: Every from one second to a year (31,536,000 seconds), and an expression that isn't blank;
 * whether Quartz takes the expression is the server's to say, beside the field, as is any field a `422 validation` names.
 * Save waits for a change, and sends the mode with its member alone, the expression trimmed. The task's other settings,
 * and whether it's enabled, stay as they are. While the save is on its way the dialog holds.
 */
export function ScheduleDialog({ job, onClose }: ScheduleDialogProps) {
  const api = useApi()
  const queryClient = useQueryClient()
  const refusalFor = useRefusalFor()
  const [start] = useState(() => startOf(job.schedule))
  const [mode, setMode] = useState<Mode>(start.mode)
  const [every, setEvery] = useState(start.every)
  const [unit, setUnit] = useState<Unit>(start.unit)
  const [cron, setCron] = useState(start.cron)
  // A field shows what's wrong with it once the person has typed in it
  const [typed, setTyped] = useState({ every: false, cron: false })
  const [failure, setFailure] = useState<unknown>()
  const [busy, setBusy] = useState(false)

  const seconds = secondsOf(every, unit)
  const expression = cron.trim()
  const codes = fieldCodes(failure)
  const everyError = (typed.every && seconds === undefined) || codes.has('intervalSeconds') ? t('admin.schedule.everyRange', { max: formatCount(maxEvery(unit)) }) : undefined
  let cronError: string | undefined
  if (codes.has('cron')) cronError = t(codes.get('cron') === 'required' ? 'admin.schedule.cronRequired' : 'admin.schedule.cronInvalid')
  else if (typed.cron && expression === '') cronError = t('admin.schedule.cronRequired')
  let change: A['JobScheduleChange'] | undefined
  if (mode === 'simple') change = seconds === undefined ? undefined : { mode, intervalSeconds: seconds }
  else change = expression === '' ? undefined : { mode, cron: expression }
  const ready = change !== undefined && !sameAs(change, job.schedule)
  // A refusal that names no field the form shows is said above the buttons, in the rule's words or the catalogue's
  const refusal = failure !== undefined && !codes.has(mode === 'simple' ? 'intervalSeconds' : 'cron') ? refusalFor(failure) : undefined

  // An edit takes the last refusal away
  function edited() {
    setFailure(undefined)
  }

  async function save(sent: A['JobScheduleChange']) {
    setBusy(true)
    setFailure(undefined)
    try {
      await jobChanged(queryClient, await saveSchedule(api, job.name, sent))
      toast(t('admin.schedule.saved', { name: job.label }))
      onClose()
    } catch (error) {
      setFailure(error)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Dialog title={t('admin.schedule.title', { name: job.label })} isOpen onOpenChange={(open) => !open && !busy && onClose()}>
      {(close) => (
        <form
          className={formStyles.form}
          onSubmit={(event) => {
            event.preventDefault()
            if (ready && !busy && change !== undefined) void save(change)
          }}
        >
          <fieldset disabled={busy} className={formStyles.hold}>
            <RadioChoices
              label={t('admin.schedule.mode')}
              value={mode}
              onChange={(value) => {
                setMode(value === 'cron' ? 'cron' : 'simple')
                edited()
              }}
              choices={[
                { value: 'simple', label: t('admin.schedule.mode.simple') },
                { value: 'cron', label: t('admin.schedule.mode.cron') },
              ]}
            />
            {mode === 'simple' ? (
              <div className={styles.every}>
                <TextField
                  label={t('admin.schedule.every')}
                  inputMode="numeric"
                  autoComplete="off"
                  autoFocus={start.mode === 'simple'}
                  required
                  value={every}
                  error={everyError}
                  description={t('admin.schedule.everyNote')}
                  onChange={(value) => {
                    setEvery(value)
                    setTyped((before) => ({ ...before, every: true }))
                    edited()
                  }}
                />
                <SelectField
                  label={t('admin.schedule.unit')}
                  value={unit}
                  onChange={(value) => {
                    setUnit(units.find((one) => one === value) ?? 'minutes')
                    edited()
                  }}
                  options={units.map((one) => ({ value: one, label: t(`admin.schedule.unit.${one}`) }))}
                />
              </div>
            ) : (
              <TextField
                label={t('admin.schedule.cron')}
                autoComplete="off"
                autoFocus={start.mode === 'cron'}
                required
                value={cron}
                error={cronError}
                description={t('admin.schedule.cronNote')}
                onChange={(value) => {
                  setCron(value)
                  setTyped((before) => ({ ...before, cron: true }))
                  edited()
                }}
              />
            )}
            <p className={formStyles.note}>{t('admin.schedule.keeps')}</p>
          </fieldset>
          <Refusal>{refusal}</Refusal>
          <div className={formStyles.buttons}>
            <Button onPress={close} isDisabled={busy}>
              {t('common.cancel')}
            </Button>
            <Button type="submit" variant="default" isDisabled={!ready} isPending={busy}>
              {t('admin.schedule.save')}
            </Button>
          </div>
        </form>
      )}
    </Dialog>
  )
}
