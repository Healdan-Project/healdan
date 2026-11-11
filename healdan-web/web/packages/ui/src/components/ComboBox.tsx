// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useContext, useEffect, type KeyboardEvent, type ReactNode, type Ref, type RefObject } from 'react'
import {
  Collection,
  ComboBox as AriaComboBox,
  ComboBoxStateContext,
  Header,
  Input,
  ListBox,
  ListBoxItem,
  ListBoxSection,
  Popover,
} from 'react-aria-components'
import styles from './ComboBox.module.css'

export interface ComboBoxOption {
  id: string
  /** The plain text a screen reader and type-ahead use */
  textValue: string
  icon?: ReactNode
  label: ReactNode
  /** Smaller and muted, at the end */
  meta?: ReactNode
}

export interface ComboBoxSection {
  id: string
  /** The section's name, which a screen reader hears; seen above its options unless `showTitle` is false */
  title: string
  showTitle?: boolean
  options: ComboBoxOption[]
}

export interface ComboBoxProps {
  'aria-label': string
  placeholder?: string
  /** What is typed: the box is controlled, and picking an option leaves it as it is */
  value: string
  onValueChange: (value: string) => void
  sections: ComboBoxSection[]
  /** An option was picked: the arrow keys and Enter, or a click */
  onSelect: (id: string) => void
  /** Enter with no option picked */
  onSubmit: (text: string) => void
  inputRef?: Ref<HTMLInputElement>
  /** What the list lines up with and takes its width from; the box itself by default */
  anchorRef?: RefObject<Element | null>
  className?: string
  inputClassName?: string
}

// Enter with no option under the arrow keys is the person's own words, which React Aria would only commit as a custom
// value and never report; the state says whether an option has the focus.
function SubmitInput({ onSubmit, value, hasOptions, ...props }: { onSubmit: (text: string) => void; value: string; hasOptions: boolean } & Record<string, unknown>) {
  const state = useContext(ComboBoxStateContext)
  // React Aria opens the list when typing changes the box and options exist at that moment; options that come in the
  // same render as the first character (the caller works them out from it) miss that moment, so it's asked again here.
  // It never opens on its own account: only after the text changed, with the box focused.
  useEffect(() => {
    if (state && state.isFocused && hasOptions && !state.isOpen) state.open(null, 'input')
    // eslint-disable-next-line react-hooks/exhaustive-deps -- only a change of the text or of having options asks
  }, [value, hasOptions])
  function onKeyDown(event: KeyboardEvent<HTMLInputElement>) {
    // Not while an input method is composing, when Enter picks a character
    if (event.key !== 'Enter' || event.nativeEvent.isComposing) return
    if (state?.selectionManager.focusedKey != null) return
    event.preventDefault()
    state?.close()
    onSubmit(event.currentTarget.value)
  }
  return <Input {...props} onKeyDown={onKeyDown} />
}

/**
 * A text box that offers options as it is typed in (a React Aria ComboBox). The options are the caller's, grouped in
 * named sections, and aren't filtered here: the caller decides what the text matches. Focus stays in the box, and the
 * arrow keys move through the options.
 */
export function ComboBox({ value, onValueChange, sections, onSelect, onSubmit, inputRef, anchorRef, className, inputClassName, placeholder, ...labels }: ComboBoxProps) {
  return (
    <AriaComboBox
      {...labels}
      className={className}
      items={sections}
      inputValue={value}
      onInputChange={onValueChange}
      // Nothing is ever the chosen one, so the same option can be picked again
      selectedKey={null}
      onSelectionChange={(key) => key !== null && onSelect(String(key))}
      allowsCustomValue
      menuTrigger="input"
    >
      <SubmitInput ref={inputRef} className={inputClassName} placeholder={placeholder} onSubmit={onSubmit} value={value} hasOptions={sections.length > 0} />
      <Popover className={styles.popover} triggerRef={anchorRef} placement="bottom start" offset={4}>
        <ListBox className={styles.list}>
          {(section: ComboBoxSection) => (
            <ListBoxSection id={section.id} className={styles.section} aria-label={section.title}>
              {section.showTitle !== false && <Header className={styles.heading}>{section.title}</Header>}
              <Collection items={section.options}>
                {(option: ComboBoxOption) => (
                  <ListBoxItem id={option.id} textValue={option.textValue} className={styles.option}>
                    {option.icon}
                    <span className={styles.label}>{option.label}</span>
                    {option.meta !== undefined && <span className={styles.meta}>{option.meta}</span>}
                  </ListBoxItem>
                )}
              </Collection>
            </ListBoxSection>
          )}
        </ListBox>
      </Popover>
    </AriaComboBox>
  )
}
