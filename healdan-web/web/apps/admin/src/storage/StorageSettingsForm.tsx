// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQueryClient } from '@tanstack/react-query'
import { useId, useState } from 'react'
import type { adminComponents } from '@healdan/api'
import { fieldErrors, useApi } from '@healdan/shell'
import { Button, TextField, toast } from '@healdan/ui'
import { t } from '../strings'
import type { AdminStringKey } from '../strings/en-US'
import { useRefusalFor } from '../strings/refusals'
import { storageKeys } from './queries'
import styles from './Storage.module.css'

type A = adminComponents['schemas']

/** The three settings, by their members' names, which a `422 validation` names too */
type Setting = keyof A['StorageSettingsChange']

const SETTINGS: { setting: Setting; label: AdminStringKey; note: AdminStringKey }[] = [
  { setting: 'holdDays', label: 'admin.storageSettings.holdDays', note: 'admin.storageSettings.holdDaysNote' },
  { setting: 'cacheGb', label: 'admin.storageSettings.cacheGb', note: 'admin.storageSettings.cacheGbNote' },
  { setting: 'transfers', label: 'admin.storageSettings.transfers', note: 'admin.storageSettings.transfersNote' },
]

const counts = new Intl.NumberFormat('en-US')

/** The whole number typed, if it is one within the setting's limits */
function valueOf(text: string, range: A['SettingRange']): number | undefined {
  const trimmed = text.trim()
  if (!/^\d+$/.test(trimmed)) return undefined
  const value = Number(trimmed)
  return value >= range.min && value <= range.max ? value : undefined
}

/**
 * The storage settings (spec 8.6), as the Storage tab's Settings: the holding period, the read cache's size and parallel
 * transfers, each a whole number within the limits the server gives, said under it, and one Save for all three. A value
 * outside them is said before Save, which is greyed out until every value is within them and one has changed. What's
 * typed stays while the window comes back; once saved, the settings are as the server answered.
 */
export function StorageSettingsForm({ settings }: { settings: A['StorageSettings'] }) {
  const api = useApi()
  const queryClient = useQueryClient()
  const refusalFor = useRefusalFor()
  const headingId = useId()
  const [typed, setTyped] = useState<Partial<Record<Setting, string>>>({})
  const [busy, setBusy] = useState(false)
  const [failure, setFailure] = useState<unknown>()

  const textOf = (setting: Setting) => typed[setting] ?? String(settings[setting])
  const rangeError = (setting: Setting) => t('admin.storageSettings.range', { min: counts.format(settings.limits[setting].min), max: counts.format(settings.limits[setting].max) })
  const values = Object.fromEntries(SETTINGS.map(({ setting }) => [setting, valueOf(textOf(setting), settings.limits[setting])])) as Record<Setting, number | undefined>
  const named = fieldErrors(failure)
  const changed = SETTINGS.some(({ setting }) => values[setting] !== settings[setting])
  const ready = SETTINGS.every(({ setting }) => values[setting] !== undefined) && changed
  // A refusal that names none of the settings is said above Save, in the rule's words or the catalogue's
  const refusal = failure !== undefined && !SETTINGS.some(({ setting }) => named.has(setting)) ? refusalFor(failure) : undefined

  async function save() {
    const { holdDays, cacheGb, transfers } = values
    if (holdDays === undefined || cacheGb === undefined || transfers === undefined) return
    setBusy(true)
    setFailure(undefined)
    try {
      const saved = await api.send<A['StorageSettings']>('PUT', '/v1/admin/storage/settings', { holdDays, cacheGb, transfers } satisfies A['StorageSettingsChange'])
      await queryClient.cancelQueries({ queryKey: storageKeys.settings })
      queryClient.setQueryData(storageKeys.settings, saved)
      setTyped({})
      toast(t('admin.storageSettings.saved'))
    } catch (error) {
      setFailure(error)
    } finally {
      setBusy(false)
    }
  }

  return (
    <section aria-labelledby={headingId} className={styles.section}>
      <h2 id={headingId}>{t('admin.storageSettings.title')}</h2>
      <form
        onSubmit={(event) => {
          event.preventDefault()
          if (ready && !busy) void save()
        }}
      >
        <fieldset className={styles.settings} disabled={busy}>
          {SETTINGS.map(({ setting, label, note }) => {
            const text = textOf(setting)
            // A value the person typed that isn't one; the server's refusal of a setting it names says the same
            const wrong = (typed[setting] !== undefined && values[setting] === undefined) || named.has(setting)
            return (
              <TextField
                key={setting}
                label={t(label)}
                inputMode="numeric"
                required
                value={text}
                error={wrong ? rangeError(setting) : undefined}
                description={t(note, { min: counts.format(settings.limits[setting].min), max: counts.format(settings.limits[setting].max) })}
                onChange={(value) => {
                  setTyped((before) => ({ ...before, [setting]: value }))
                  // A change takes the last refusal away
                  setFailure(undefined)
                }}
              />
            )
          })}
          {refusal !== undefined && (
            <p className={styles.refusal} role="alert">
              {refusal}
            </p>
          )}
          <div className={styles.row}>
            <Button type="submit" variant="default" isDisabled={!ready} isPending={busy}>
              {t('admin.storageSettings.save')}
            </Button>
          </div>
        </fieldset>
      </form>
    </section>
  )
}
