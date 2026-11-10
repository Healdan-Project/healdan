// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { ReactNode } from 'react'
import { Dialog as AriaDialog, Heading, Modal, ModalOverlay } from 'react-aria-components'
import styles from './Dialog.module.css'

export interface DialogProps {
  title: string
  isOpen: boolean
  onOpenChange: (isOpen: boolean) => void
  /** The dialog's buttons, given a function that closes it. */
  children: ReactNode | ((close: () => void) => ReactNode)
}

// A modal dialog: focus stays inside it, Esc closes it, and focus goes back to where it came from.
export function Dialog({ title, isOpen, onOpenChange, children }: DialogProps) {
  return (
    <ModalOverlay className={styles.overlay} isOpen={isOpen} onOpenChange={onOpenChange} isDismissable>
      <Modal className={styles.modal}>
        <AriaDialog className={styles.dialog}>
          {({ close }) => (
            <>
              <Heading slot="title" className={styles.title}>
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
