// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useId } from 'react'
import { FieldError, Input, Label, TextArea, TextField as AriaTextField } from 'react-aria-components'
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
  /** `number` for a whole or decimal number; text by default. A multi-line field is always text */
  type?: 'text' | 'number'
  /** The keyboard a phone shows; `decimal` for a number typed as text, which a form can then check itself */
  inputMode?: 'decimal' | 'numeric'
  /** Takes the focus when it first draws, as the first field of a dialog does */
  autoFocus?: boolean
  /** Several lines, such as a description, where Enter starts a new line */
  multiline?: boolean
  /** How many lines a multi-line field shows before it scrolls; 3 by default */
  rows?: number
}

function Mark({ required }: { required?: boolean }) {
  return required ? (
    <span className={styles.mark} aria-hidden>
      {' *'}
    </span>
  ) : null
}

/** A text field with its label above it: one line, or several when `multiline`. */
export function TextField({ label, value, onChange, type = 'text', inputMode, autoFocus, required, error, multiline = false, rows = 3 }: TextFieldProps) {
  return (
    <AriaTextField
      className={styles.field}
      autoFocus={autoFocus}
      value={value}
      onChange={onChange}
      type={multiline ? undefined : type}
      isRequired={required}
      validationBehavior="aria"
      isInvalid={error ? true : undefined}
    >
      <Label className={styles.label}>
        {label}
        <Mark required={required} />
      </Label>
      {multiline ? (
        <TextArea className={`${styles.input} ${styles.multiline}`} inputMode={inputMode} rows={rows} />
      ) : (
        <Input className={styles.input} inputMode={inputMode} step={type === 'number' ? 'any' : undefined} />
      )}
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

/** A yes or no. `required` marks it as one to answer: either answer is an answer, so it doesn't have to be ticked. */
export function CheckField({ label, checked, onChange, required, error }: CheckFieldProps) {
  const id = useId()
  const errorId = `${id}-error`
  return (
    <div className={styles.field}>
      <label className={styles.check} htmlFor={id}>
        <input id={id} type="checkbox" checked={checked} aria-invalid={error ? true : undefined} aria-describedby={error ? errorId : undefined} onChange={(event) => onChange(event.target.checked)} />
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
