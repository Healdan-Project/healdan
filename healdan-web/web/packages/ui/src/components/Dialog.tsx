// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { ReactNode } from 'react'
import { Dialog as AriaDialog, Heading, Modal, ModalOverlay } from 'react-aria-components'
import { Icon } from '../icons'
import styles from './Dialog.module.css'

export interface DialogProps {
  title: string
  isOpen: boolean
  /** `wide` is for a dialog that holds a table; it still fits the window less its margins */
  size?: 'default' | 'wide'
  /**
   * For a question before something the person might not want, and can't take back themselves: an alert, which a screen
   * reader says is one, with the warning sign before its title (decoration, so it isn't part of the dialog's name)
   */
  warning?: boolean
  /** The id of what a screen reader reads after the title as the dialog opens, as an alert's warning */
  'aria-describedby'?: string
  onOpenChange: (isOpen: boolean) => void
  /** The dialog's buttons, given a function that closes it. */
  children: ReactNode | ((close: () => void) => ReactNode)
}

// A modal dialog: focus stays inside it, Esc closes it, and focus goes back to where it came from.
export function Dialog({ title, isOpen, onOpenChange, size = 'default', warning = false, 'aria-describedby': describedBy, children }: DialogProps) {
  return (
    <ModalOverlay className={styles.overlay} isOpen={isOpen} onOpenChange={onOpenChange} isDismissable>
      <Modal className={size === 'wide' ? `${styles.modal} ${styles.wide}` : styles.modal}>
        <AriaDialog role={warning ? 'alertdialog' : 'dialog'} aria-describedby={describedBy} className={styles.dialog}>
          {({ close }) => (
            <>
              <Heading slot="title" className={warning ? `${styles.title} ${styles.withIcon}` : styles.title}>
                {warning && <Icon name="alertTriangle" size={24} className={styles.warningSign} />}
                {title}
              </Heading>
              {typeof children === 'function' ? children(close) : children}
            </>
          )}
        </AriaDialog>
      </Modal>
    </ModalOverlay>
  )
}
