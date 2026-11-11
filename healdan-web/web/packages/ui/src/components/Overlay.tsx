// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { ReactNode } from 'react'
import { Dialog as AriaDialog, Modal, ModalOverlay } from 'react-aria-components'
import { Icon } from '../icons'
import { t } from '../strings'
import { Button } from './Button'
import styles from './Overlay.module.css'

export interface OverlayProps {
  /** The dialog's name for a screen reader */
  label: string
  isOpen: boolean
  onClose: () => void
  /** Moves to the previous item of the list behind it; none disables the button, as at the list's start */
  onPrevious?: () => void
  onNext?: () => void
  /** What fills the bar between the arrows and the actions: the item's name and its place */
  heading?: ReactNode
  /** The bar's buttons, before Close */
  actions?: ReactNode
  children: ReactNode
}

/** A key's target is something that has the key for itself: a field, or a button that Space presses. */
function keepsKey(target: EventTarget | null, key: string): boolean {
  if (!(target instanceof Element)) return false
  if (target.closest('input, textarea, select, [contenteditable="true"]')) return true
  return key === ' ' && target.closest('button, a, [role="button"], [role="link"]') !== null
}

/**
 * Quick Look's frame (spec section 6.4): a large modal over the app, as Finder's is, with Previous and Next for the list
 * behind it. The arrow keys walk the list; Space and Esc close it. Focus stays inside and goes back to where it came
 * from, and nothing behind it moves.
 */
export function Overlay({ label, isOpen, onClose, onPrevious, onNext, heading, actions, children }: OverlayProps) {
  function onKeyDown(event: KeyboardEvent) {
    if (event.altKey || event.ctrlKey || event.metaKey || event.shiftKey || keepsKey(event.target, event.key)) return
    let handled = true
    if (event.key === ' ') onClose()
    else if (event.key === 'ArrowLeft' || event.key === 'ArrowUp') onPrevious?.()
    else if (event.key === 'ArrowRight' || event.key === 'ArrowDown') onNext?.()
    else handled = false
    if (handled) event.preventDefault()
  }

  return (
    <ModalOverlay className={styles.overlay} isOpen={isOpen} onOpenChange={(open) => !open && onClose()} isDismissable>
      <Modal className={styles.modal}>
        {/* React Aria's Dialog passes no key handler on, so the dialog's own element is listened to (it has the focus
            first, so a key pressed there comes to it) */}
        <AriaDialog
          className={styles.dialog}
          aria-label={label}
          ref={(node) => {
            node?.addEventListener('keydown', onKeyDown)
            return () => node?.removeEventListener('keydown', onKeyDown)
          }}
        >
          <div className={styles.bar}>
            <Button variant="quiet" aria-label={t('preview.previous')} isDisabled={!onPrevious} onPress={onPrevious}>
              <Icon name="chevronLeft" size={18} />
            </Button>
            <Button variant="quiet" aria-label={t('preview.next')} isDisabled={!onNext} onPress={onNext}>
              <Icon name="chevronRight" size={18} />
            </Button>
            <div className={styles.heading}>{heading}</div>
            <div className={styles.actions}>{actions}</div>
            <Button variant="quiet" aria-label={t('preview.close')} onPress={onClose}>
              <Icon name="close" size={18} />
            </Button>
          </div>
          <div className={styles.body}>{children}</div>
        </AriaDialog>
      </Modal>
    </ModalOverlay>
  )
}
