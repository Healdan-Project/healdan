// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useId, useLayoutEffect, useRef, type ReactNode, type RefObject } from 'react'
import { Dialog as AriaDialog, DEFAULT_SLOT, Popover, TextContext } from 'react-aria-components'
import { Icon } from '../icons'
import { t } from '../strings'
import { Button } from './Button'
import menuStyles from './Menu.module.css'
import styles from './Table.module.css'

interface ColumnsButtonProps {
  placeRef: RefObject<HTMLDivElement | null>
  buttonRef: RefObject<HTMLButtonElement | null>
  /** Its panel is open */
  isOpen: boolean
  onPress: () => void
}

/**
 * A document list's Columns button (spec 4.5, 4.9), at the heading row's end: drawn as the toolbar's icon buttons are (More's),
 * named Columns, with its name as its tooltip. The Table puts it after the list, as Tab reaches it, outside every heading: it
 * isn't a column, and nothing drags from it.
 */
export function ColumnsButton({ placeRef, buttonRef, isOpen, onPress }: ColumnsButtonProps) {
  return (
    <div ref={placeRef} className={styles.columnsButton} title={t('columns.button')}>
      <Button ref={buttonRef} aria-label={t('columns.button')} aria-haspopup="dialog" aria-expanded={isOpen} onPress={onPress}>
        <Icon name="columns" size={14} />
      </Button>
    </div>
  )
}

interface ColumnsPopoverProps {
  /** The Columns button, which the popover is placed by */
  triggerRef: RefObject<HTMLButtonElement | null>
  isOpen: boolean
  onOpenChange: (isOpen: boolean) => void
  /** The Columns panel */
  children: ReactNode
}

/**
 * The popover that holds the Columns panel, below the button at its end, whichever of the button and Choose Columns… opened
 * it: a dialog the panel's `Heading slot="title"` names and its `Text slot="description"` describes. The panel changes the
 * list at once, so the popover stays open, its focus where it was, as the list is drawn again. The Table draws it outside the
 * list's menus and handlers, which the panel's events would otherwise reach through React's tree.
 */
export function ColumnsPopover({ triggerRef, isOpen, onOpenChange, children }: ColumnsPopoverProps) {
  const description = useId()
  const dialog = useRef<HTMLElement>(null)
  // The dialog is described only by a description the panel holds, which it may not. React Aria's Dialog takes one only as a
  // prop, drawn before the panel is, so it's set here, before the dialog takes the focus (a passive effect of React Aria's).
  useLayoutEffect(() => {
    const element = dialog.current
    if (!element) return
    if (element.querySelector(`[id="${description}"]`)) element.setAttribute('aria-describedby', description)
    else element.removeAttribute('aria-describedby')
  })
  return (
    <Popover className={`${menuStyles.popover} ${styles.panelPopover}`} triggerRef={triggerRef} isOpen={isOpen} onOpenChange={onOpenChange} placement="bottom end">
      <AriaDialog ref={dialog} className={styles.panelDialog}>
        <TextContext.Provider value={{ slots: { [DEFAULT_SLOT]: {}, description: { id: description } } }}>{children}</TextContext.Provider>
      </AriaDialog>
    </Popover>
  )
}
