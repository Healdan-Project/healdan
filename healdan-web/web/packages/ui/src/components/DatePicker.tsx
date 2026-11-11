// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { parseDate, type DateValue } from '@internationalized/date'
import { useState } from 'react'
import {
  Button as AriaButton,
  Calendar,
  CalendarCell,
  CalendarGrid,
  DateInput,
  DatePicker as AriaDatePicker,
  DateSegment,
  Dialog,
  FieldError,
  Group,
  Heading,
  Label,
  Popover,
} from 'react-aria-components'
import { Icon } from '../icons'
import styles from './DatePicker.module.css'

export interface DatePickerProps {
  label: string
  /** A day as 2026-11-02, or none */
  value?: string
  onChange: (value: string | undefined) => void
  /** The earliest and latest day it takes, as 2026-11-02 */
  min?: string
  max?: string
  /** A required day has a mark after its label, and says so to a screen reader */
  required?: boolean
  /** What is wrong with the day, shown under the field and tied to it for a screen reader */
  error?: string
}

function day(text?: string) {
  if (!text) return undefined
  try {
    return parseDate(text)
  } catch {
    return undefined
  }
}

/** A day typed in parts (month, day, year in the person's order), or picked from a calendar. Days go in and out as 2026-11-02. */
export function DatePicker({ label, value, onChange, min, max, required, error }: DatePickerProps) {
  // The field keeps what is typed so far, which may not be a day the caller should hear of yet. It's replaced when the
  // caller's value changes (compared with the caller's last value, not with what the field reported, so a caller that
  // keeps its own value while the field is half typed doesn't blank it), adjusted during render, not in an effect.
  const [shown, setShown] = useState<DateValue | null>(day(value) ?? null)
  const [seen, setSeen] = useState(value)
  if (value !== seen) {
    setSeen(value)
    setShown(day(value) ?? null)
  }
  return (
    <AriaDatePicker
      className={styles.picker}
      value={shown}
      onChange={(next) => {
        setShown(next)
        // Typing the year goes through 0002, 0020 and 0202 on the way to 2026; none of them is the day meant.
        if (next && next.year < 1000) return
        onChange(next ? next.toString() : undefined)
      }}
      isInvalid={error ? true : undefined}
      isRequired={required}
      minValue={day(min)}
      maxValue={day(max)}
    >
      <Label className={styles.label}>
        {label}
        {required && <span aria-hidden> *</span>}
      </Label>
      <Group className={styles.group}>
        <DateInput className={styles.input}>{(segment) => <DateSegment className={styles.segment} segment={segment} />}</DateInput>
        <AriaButton className={styles.open}>
          <Icon name="calendar" />
        </AriaButton>
      </Group>
      <FieldError className={styles.error}>{error}</FieldError>
      <Popover className={styles.popover}>
        <Dialog className={styles.dialog}>
          <Calendar>
            <header className={styles.header}>
              <AriaButton slot="previous" className={styles.step}>
                <Icon name="chevronLeft" />
              </AriaButton>
              <Heading className={styles.heading} />
              <AriaButton slot="next" className={styles.step}>
                <Icon name="chevronRight" />
              </AriaButton>
            </header>
            <CalendarGrid className={styles.grid}>{(date) => <CalendarCell className={styles.cell} date={date} />}</CalendarGrid>
          </Calendar>
        </Dialog>
      </Popover>
    </AriaDatePicker>
  )
}
