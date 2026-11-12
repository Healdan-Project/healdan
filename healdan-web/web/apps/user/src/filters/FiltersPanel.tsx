// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useState } from 'react'
import type { components } from '@healdan/api'
import { Button, CheckGroup, DatePicker, Icon, RadioChoices, t, type Choice, type StringKey } from '@healdan/ui'
import { datePresets, fileTypes, type Filters, type MultiFilter } from './useFilters'
import styles from './filters.module.css'

type S = components['schemas']

export interface FiltersPanelProps {
  filters: Filters
  /** Each option's count; undefined while they are asked for */
  facets?: S['Facets']
  /** The counts couldn't be had: the options show without them */
  unavailable?: boolean
  /** The counts shown are the last ones, while new ones are asked for */
  busy?: boolean
  /** A search offers Client and {matter} as well; a folder is in one already */
  context: 'folder' | 'search'
  onChange: (patch: Partial<Filters>) => void
  onClose: () => void
}

/** What an option is called: the file kinds have words of their own. */
export function typeLabel(value: string): string {
  return fileTypes.some((kind) => kind === value) ? t(`filters.type.${value}` as StringKey) : value
}

// The options of one group: what the server counted, then any ticked value it didn't (a filter from the address that
// nothing offers now), so every filter in use can be unticked here and none hides. Counts over only the most relevant
// matches (`partial`) can leave out a value that has matches further down, so one of those is shown with no count
// rather than a 0 that would be untrue.
function choicesFor(
  offered: { value: string; label: string; count: number }[] | undefined,
  ticked: string[],
  label: (value: string) => string,
  partial: boolean,
): Choice[] {
  const choices: Choice[] = (offered ?? []).map(({ value, label, count }) => ({ value, label, count, name: t('filters.optionName', { label, count }) }))
  for (const value of ticked) {
    if (choices.some((choice) => choice.value === value)) continue
    // With counts known, an option they don't offer would leave none
    choices.push(offered && !partial ? { value, label: label(value), count: 0, name: t('filters.optionName', { label: label(value), count: 0 }) } : { value, label: label(value) })
  }
  return choices
}

function rangeKey(range: { from?: string; to?: string }): string {
  return `${range.from ?? ''}|${range.to ?? ''}`
}

const presetChoices: Choice[] = [
  { value: 'any', label: t('filters.date.any') },
  ...datePresets.map((preset) => ({ value: preset, label: t(`filters.date.${preset}` as StringKey) })),
  { value: 'between', label: t('filters.date.between') },
]

/** Filters, in the right region: Edit Date, then a tick list for each of the others, each option with its count. */
export function FiltersPanel({ filters, facets, unavailable, busy, context, onChange, onClose }: FiltersPanelProps) {
  // Between… chosen but no day given yet is no filter, so it isn't in the address; the panel remembers it.
  const [between, setBetween] = useState(false)
  // The range being edited is the panel's own until it is a good one: typing a month goes through others on the way,
  // and one end can pass the other for a moment. Only a good range goes to the address, which takes a render or two to
  // answer, so neither field is ever shown from the address while it is being typed in.
  const [draft, setDraft] = useState<{ from?: string; to?: string }>({ from: filters.from, to: filters.to })
  const [seen, setSeen] = useState(rangeKey(filters))
  if (rangeKey(filters) !== seen) {
    // The range changed from outside (a chip's ×, Clear All, a preset): the draft follows it
    setSeen(rangeKey(filters))
    setDraft({ from: filters.from, to: filters.to })
    if (!filters.from && !filters.to) setBetween(false)
  }
  const reversed = Boolean(draft.from && draft.to && draft.from > draft.to)
  const date = draft.from || draft.to || between ? 'between' : (filters.edited ?? 'any')

  function setRange(next: { from?: string; to?: string }) {
    setDraft(next)
    if (next.from && next.to && next.from > next.to) return
    // What the address will hold is what the panel has now, so its arrival isn't "from outside"
    setSeen(rangeKey(next))
    onChange({ from: next.from, to: next.to })
  }

  function chooseDate(value: string) {
    if (value === 'between') {
      setBetween(true)
      onChange({ edited: undefined })
      return
    }
    setBetween(false)
    onChange({ edited: value === 'any' ? undefined : (datePresets.find((preset) => preset === value) ?? undefined), from: undefined, to: undefined })
  }

  function group(kind: MultiFilter, label: string, offered: { value: string; label: string; count: number }[] | undefined, name: (value: string) => string = (value) => value) {
    const choices = choicesFor(offered, filters[kind], name, facets?.partial === true)
    if (choices.length === 0) return null
    return <CheckGroup key={kind} label={label} choices={choices} value={filters[kind]} onChange={(value) => onChange({ [kind]: value })} />
  }

  return (
    <div className={styles.panel}>
      <div className={styles.head}>
        <h2 className={styles.title}>{t('filters.title')}</h2>
        <Button variant="quiet" aria-label={t('filters.close')} onPress={onClose}>
          <Icon name="close" />
        </Button>
      </div>
      {facets?.partial && <p className={styles.note}>{t('filters.partial')}</p>}
      <div className={styles.group}>
        <RadioChoices label={t('filters.editDate')} choices={presetChoices} value={date} onChange={chooseDate} />
        {date === 'between' && (
          <div className={styles.range}>
            <DatePicker label={t('filters.date.from')} value={draft.from} onChange={(from) => setRange({ ...draft, from })} />
            <DatePicker
              label={t('filters.date.to')}
              value={draft.to}
              error={reversed ? t('filters.date.reversed') : undefined}
              onChange={(to) => setRange({ ...draft, to })}
            />
          </div>
        )}
      </div>
      <div aria-busy={busy || undefined} className={busy ? styles.busy : undefined}>
        {[
          group('type', t('filters.type'), facets?.type?.map(({ value, count }) => ({ value, label: typeLabel(value), count })), typeLabel),
          group('author', t('filters.author'), facets?.author?.map(({ value, displayName, count }) => ({ value, label: displayName, count }))),
          group('class', t('filters.class'), facets?.class?.map(({ value, count }) => ({ value, label: value, count }))),
          ...(context === 'search'
            ? [
                group('client', t('filters.client'), facets?.client?.map(({ value, count }) => ({ value, label: value, count }))),
                group('matter', t('filters.matter'), facets?.matter?.map(({ value, count }) => ({ value, label: value, count }))),
              ]
            : []),
        ].map((one, i) => one && <div key={i} className={styles.group}>{one}</div>)}
      </div>
      {!facets && <p className={styles.note}>{unavailable ? t('filters.unavailable') : t('common.loading')}</p>}
    </div>
  )
}
