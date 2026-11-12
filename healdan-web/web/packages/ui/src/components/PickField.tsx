// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useState, type Key } from 'react'
import { ComboBox as AriaComboBox, FieldError, Input, Label, ListBox, ListBoxItem, Popover } from 'react-aria-components'
import comboStyles from './ComboBox.module.css'
import styles from './Fields.module.css'

export interface PickFieldOption {
  id: string
  label: string
}

export interface PickFieldProps {
  label: string
  /** A required field has a mark after its label, and says so to a screen reader */
  required?: boolean
  /** What is wrong with it, shown under it and tied to it for a screen reader */
  error?: string
  /** What is picked, or null for nothing */
  value: PickFieldOption | null
  /** Something else was picked, or the box was emptied and left (null) */
  onChange: (value: PickFieldOption | null) => void
  /** The options for what is typed, which the caller finds; the value is offered first while something is typed */
  options: PickFieldOption[]
  /** What is typed, as it changes, for the caller to find options for; the value's label while nothing is typed */
  onTextChange: (text: string) => void
}

/**
 * A field that picks one of many, found as the person types (a React Aria ComboBox with its label above it, as the
 * other fields have): an option is picked with the arrow keys and Enter, or a click. Text left half typed goes back to
 * what was picked; an emptied box picks nothing.
 */
export function PickField({ label, required, error, value, onChange, options, onTextChange }: PickFieldProps) {
  const [text, setText] = useState(value?.label ?? '')
  // A value changed from outside (the form opening again) shows its label: set as it draws, not in an effect
  const [shown, setShown] = useState(value)
  if (shown?.id !== value?.id || shown?.label !== value?.label) {
    setShown(value)
    setText(value?.label ?? '')
  }
  // The value is offered first while something is typed. An emptied box offers nothing: React Aria reports picking the
  // value again from a list as it reports leaving the box, so an emptied box with the value in its list couldn't tell
  // the two apart
  const items = value && text.trim() !== '' ? [value, ...options.filter((option) => option.id !== value.id)] : options

  function type(next: string) {
    setText(next)
    onTextChange(next)
  }

  function pick(key: Key | null) {
    if (key === null) {
      empty()
      return
    }
    // Leaving the box also calls this with what was picked: half-typed text goes back to it, and an emptied box is left
    // to leave(), as it offers nothing to pick
    if (key === value?.id && text.trim() === '') return
    const picked = items.find((option) => option.id === String(key))
    if (!picked) return
    setText(picked.label)
    onTextChange('')
    if (picked.id !== value?.id) onChange(picked)
  }

  function empty() {
    setText('')
    onTextChange('')
    if (value) onChange(null)
  }

  // An emptied box left by Tab or a click elsewhere picks nothing
  function leave() {
    if (text.trim() === '') empty()
  }

  return (
    <AriaComboBox
      className={styles.field}
      items={items}
      inputValue={text}
      onInputChange={type}
      selectedKey={value?.id ?? null}
      onSelectionChange={pick}
      isRequired={required}
      validationBehavior="aria"
      isInvalid={error ? true : undefined}
      menuTrigger="input"
    >
      <Label className={styles.label}>
        {label}
        {required && (
          <span className={styles.mark} aria-hidden>
            {' *'}
          </span>
        )}
      </Label>
      <Input className={styles.input} onBlur={leave} />
      <FieldError className={styles.error}>{error}</FieldError>
      <Popover className={comboStyles.popover} placement="bottom start" offset={4}>
        <ListBox className={comboStyles.list}>
          {(option: PickFieldOption) => (
            <ListBoxItem id={option.id} textValue={option.label} className={comboStyles.option}>
              <span className={comboStyles.label}>{option.label}</span>
            </ListBoxItem>
          )}
        </ListBox>
      </Popover>
    </AriaComboBox>
  )
}
