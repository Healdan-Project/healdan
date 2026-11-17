// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { ReactNode } from 'react'
import { Dialog as AriaDialog, Heading, Modal, ModalOverlay } from 'react-aria-components'
import styles from './Dialog.module.css'

export interface DialogProps {
  title: string
  isOpen: boolean
  /** `wide` is for a dialog that holds a table; it still fits the window less its margins */
  size?: 'default' | 'wide'
  /** `alertdialog` is for one that warns of something the person might not want, so a screen reader says so */
  role?: 'dialog' | 'alertdialog'
  /** Drawn before the title, as an alert's warning sign; decoration, so it isn't part of the dialog's name */
  icon?: ReactNode
  onOpenChange: (isOpen: boolean) => void
  /** The dialog's buttons, given a function that closes it. */
  children: ReactNode | ((close: () => void) => ReactNode)
}

// A modal dialog: focus stays inside it, Esc closes it, and focus goes back to where it came from.
export function Dialog({ title, isOpen, onOpenChange, size = 'default', role = 'dialog', icon, children }: DialogProps) {
  return (
    <ModalOverlay className={styles.overlay} isOpen={isOpen} onOpenChange={onOpenChange} isDismissable>
      <Modal className={size === 'wide' ? `${styles.modal} ${styles.wide}` : styles.modal}>
        <AriaDialog role={role} className={styles.dialog}>
          {({ close }) => (
            <>
              <Heading slot="title" className={icon ? `${styles.title} ${styles.withIcon}` : styles.title}>
                {icon}
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
