// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useContext, useState, type Key } from 'react'
import { ComboBox as AriaComboBox, ComboBoxStateContext, FieldError, Input, Label, ListBox, ListBoxItem, type InputProps } from 'react-aria-components'
import { Icon, type IconName } from '../icons'
import { ListPopover, useListLayer } from './ComboBox'
import comboStyles from './ComboBox.module.css'
import { OpensWhenOptionsCome } from './OpensWhenOptionsCome'
import styles from './Fields.module.css'

export interface PickFieldOption {
  id: string
  /** What the box shows once it is picked */
  label: string
  /** Drawn before the label, as decoration */
  icon?: IconName
  /** Smaller and muted after the label, and part of the option's name (a person's username, a matter's client); the box shows only the label */
  meta?: string
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

// The Esc that closes the list is the list's. With something picked, React Aria lets it go on to what is round the field
// (a dialog, or a form that closes on Esc, as PropertiesForm does), so it's marked as taken (defaultPrevented), and only
// the list closes. Esc with the list closed goes on as before. Not while an input method is composing, which takes Esc.
function PickInput(props: InputProps) {
  const state = useContext(ComboBoxStateContext)
  return (
    <Input
      {...props}
      onKeyDown={(event) => {
        if (event.key === 'Escape' && !event.nativeEvent.isComposing && state?.isOpen) event.preventDefault()
      }}
    />
  )
}

/**
 * A field that picks one of many, found as the person types (a React Aria ComboBox with its label above it, as the
 * other fields have): an option is picked with the arrow keys and Enter, or a click. Text left half typed goes back to
 * what was picked; an emptied box picks nothing. Its list is drawn as ComboBox's is (ListPopover), right after the field,
 * with the page around it left shown to a screen reader.
 */
export function PickField({ label, required, error, value, onChange, options, onTextChange }: PickFieldProps) {
  const [layer, layerElement] = useListLayer()
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
  // Options found for what was typed, which may come once the typing has stopped (UserField's, from the server), and open
  // the list then; never for the picked one's own name, which the box shows once it is picked
  const found = options.length > 0 && text.trim() !== '' && text !== value?.label

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
    <>
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
        <PickInput className={styles.input} onBlur={leave} />
        <OpensWhenOptionsCome text={text} hasOptions={found} />
        <FieldError className={styles.error}>{error}</FieldError>
        <ListPopover layer={layer}>
          <ListBox className={comboStyles.list}>
            {(option: PickFieldOption) => (
              <ListBoxItem id={option.id} textValue={option.label} className={comboStyles.option}>
                {option.icon && <Icon name={option.icon} />}
                {/* One text, so the name is the label, a space, then the secondary text, as a screen reader reads what is drawn */}
                <span className={comboStyles.label}>
                  {option.label}
                  {option.meta && (
                    <>
                      {' '}
                      <span className={comboStyles.meta}>{option.meta}</span>
                    </>
                  )}
                </span>
              </ListBoxItem>
            )}
          </ListBox>
        </ListPopover>
      </AriaComboBox>
      {layerElement}
    </>
  )
}
