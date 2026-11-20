// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useContext, useEffect, useRef, useState, type KeyboardEvent, type MutableRefObject, type ReactNode, type Ref, type RefObject } from 'react'
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
function SubmitInput({ onSubmit, value, hasOptions, closerRef, ...props }: { onSubmit: (text: string) => void; value: string; hasOptions: boolean; closerRef: MutableRefObject<(() => void) | null> } & Record<string, unknown>) {
  const state = useContext(ComboBoxStateContext)
  // The caller's pick closes the list from outside the state
  useEffect(() => {
    closerRef.current = () => state?.close()
    return () => {
      closerRef.current = null
    }
  }, [closerRef, state])
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

// React Aria's mark for a layer it leaves shown when an overlay hides the rest of the page (its toasts' region carries it)
const SHOWN_LAYER = 'data-react-aria-top-layer'

/**
 * While its list is open, React Aria's combo box hides everything but the box and the list from screen readers
 * (`ariaHideOutside` in its `useComboBox`), because it draws the list at the end of <body>, where a screen reader would
 * reach it only past the rest of the page. Here the list is drawn right after the box, as WAI-ARIA's combo box examples
 * have it, so there's nothing to hide: the page stays readable while the list is open, a screen reader's cursor can move on
 * from the list to what comes next, and nothing hidden holds a link a keyboard reaches (axe's aria-hidden-focus). React
 * Aria has no switch for its hiding yet (adobe/react-spectrum#4937, where its team means to add one to `useComboBox` for a
 * list that isn't drawn at the end of <body>), so while it looks, the page's layer (the element of <body> that holds the
 * box) carries the mark React Aria leaves shown, and it's taken off straight after.
 *
 * React Aria looks once, in an effect of its combo box, as the list opens: what is added to the page later it hides only
 * outside what it left shown then. This runs in an effect of a child of that combo box, which runs first, as a child's
 * effects run before its parent's, and the mark comes off in a microtask, once the pass that runs both effects has ended.
 */
function PageStaysShown({ near }: { near: HTMLElement | null }) {
  const isOpen = useContext(ComboBoxStateContext)?.isOpen ?? false
  useEffect(() => {
    if (!isOpen || near === null) return
    let layer: HTMLElement = near
    while (layer.parentElement !== null && layer.parentElement !== near.ownerDocument.body) layer = layer.parentElement
    if (layer.parentElement === null || layer.hasAttribute(SHOWN_LAYER)) return
    layer.setAttribute(SHOWN_LAYER, '')
    queueMicrotask(() => layer.removeAttribute(SHOWN_LAYER))
  }, [isOpen, near])
  return null
}

/**
 * A text box that offers options as it is typed in (a React Aria ComboBox). The options are the caller's, grouped in
 * named sections, and aren't filtered here: the caller decides what the text matches. Focus stays in the box, and the
 * arrow keys move through the options. The list comes right after the box in the page, so it is in the caller's landmark,
 * and the page around it stays shown to a screen reader while it's open (see PageStaysShown). It is drawn in a layer fixed
 * over the window (`.layer`), so nothing round the box can cut it off or move it, as at the end of <body>, where React Aria
 * would draw it.
 */
export function ComboBox({ value, onValueChange, sections, onSelect, onSubmit, inputRef, anchorRef, className, inputClassName, placeholder, ...labels }: ComboBoxProps) {
  const closerRef = useRef<(() => void) | null>(null)
  // The list's layer, after React Aria's combo box rather than in it, as React Aria draws its children twice (once in a
  // hidden copy that works out the options)
  const [layer, setLayer] = useState<HTMLDivElement | null>(null)
  return (
    <>
      <AriaComboBox
        {...labels}
        className={className}
        items={sections}
        inputValue={value}
        onInputChange={onValueChange}
        // Nothing is ever the chosen one, so the same option can be picked again
        selectedKey={null}
        onSelectionChange={(key) => {
          if (key === null) return
          onSelect(String(key))
          // The text stays whatever the caller does with it, and options for it may still be there, so the list is closed
          // here, after this key's other handlers, which read which option had the focus
          queueMicrotask(() => closerRef.current?.())
        }}
        allowsCustomValue
        menuTrigger="input"
      >
        <SubmitInput ref={inputRef} className={inputClassName} placeholder={placeholder} onSubmit={onSubmit} value={value} hasOptions={sections.length > 0} closerRef={closerRef} />
        <PageStaysShown near={layer} />
        <Popover className={styles.popover} triggerRef={anchorRef} placement="bottom start" offset={4} UNSTABLE_portalContainer={layer ?? undefined}>
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
      <div ref={setLayer} className={styles.layer} />
    </>
  )
}
