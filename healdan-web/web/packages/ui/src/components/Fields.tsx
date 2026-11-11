// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useId } from 'react'
import { FieldError, Input, Label, TextField as AriaTextField } from 'react-aria-components'
import styles from './Fields.module.css'

interface FieldProps {
  label: string
  /** A required field has a mark after its label, and says so to a screen reader */
  required?: boolean
  /** What is wrong with it, shown under it and tied to it for a screen reader */
  error?: string
}

export interface TextFieldProps extends FieldProps {
  value: string
  onChange: (value: string) => void
  /** `number` for a whole or decimal number; text by default */
  type?: 'text' | 'number'
}

function Mark({ required }: { required?: boolean }) {
  return required ? (
    <span className={styles.mark} aria-hidden>
      {' *'}
    </span>
  ) : null
}

/** A one-line field with its label above it. */
export function TextField({ label, value, onChange, type = 'text', required, error }: TextFieldProps) {
  return (
    <AriaTextField className={styles.field} value={value} onChange={onChange} type={type} isRequired={required} validationBehavior="aria" isInvalid={error ? true : undefined}>
      <Label className={styles.label}>
        {label}
        <Mark required={required} />
      </Label>
      <Input className={styles.input} step={type === 'number' ? 'any' : undefined} />
      <FieldError className={styles.error}>{error}</FieldError>
    </AriaTextField>
  )
}

export interface SelectFieldProps extends FieldProps {
  value: string
  onChange: (value: string) => void
  options: { value: string; label: string }[]
  /** The text of a choice that leaves it with no value; without it the list offers only its options */
  empty?: string
}

/** A pick from a list: it offers only its options, and `empty` if the field may have none. */
export function SelectField({ label, value, onChange, options, empty, required, error }: SelectFieldProps) {
  const id = useId()
  const errorId = `${id}-error`
  // A value the list no longer has stays shown, so it isn't silently replaced by the first option
  const known = value === '' || options.some((option) => option.value === value)
  return (
    <div className={styles.field}>
      <label className={styles.label} htmlFor={id}>
        {label}
        <Mark required={required} />
      </label>
      <select
        id={id}
        className={styles.input}
        value={value}
        required={required}
        aria-invalid={error ? true : undefined}
        aria-describedby={error ? errorId : undefined}
        onChange={(event) => onChange(event.target.value)}
      >
        {empty !== undefined && <option value="">{empty}</option>}
        {value === '' && empty === undefined && <option value="" disabled hidden />}
        {!known && <option value={value}>{value}</option>}
        {options.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>
      {error && (
        <span id={errorId} className={styles.error}>
          {error}
        </span>
      )}
    </div>
  )
}

export interface CheckFieldProps extends FieldProps {
  checked: boolean
  onChange: (checked: boolean) => void
}

/** A yes or no. */
export function CheckField({ label, checked, onChange, required, error }: CheckFieldProps) {
  const id = useId()
  const errorId = `${id}-error`
  return (
    <div className={styles.field}>
      <label className={styles.check} htmlFor={id}>
        <input id={id} type="checkbox" checked={checked} required={required} aria-invalid={error ? true : undefined} aria-describedby={error ? errorId : undefined} onChange={(event) => onChange(event.target.checked)} />
        <span>
          {label}
          <Mark required={required} />
        </span>
      </label>
      {error && (
        <span id={errorId} className={styles.error}>
          {error}
        </span>
      )}
    </div>
  )
}
