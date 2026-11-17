// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { Ref } from 'react'
import { Button as AriaButton, ProgressBar, type ButtonProps as AriaButtonProps } from 'react-aria-components'
import { t } from '../strings'
import styles from './Button.module.css'

export interface ButtonProps extends Omit<AriaButtonProps, 'className' | 'style'> {
  /**
   * The default button is the accent one; secondary has a field-coloured face; quiet has none, for an icon in a row;
   * destructive is red, for a confirmation that takes something away for good, as Remove does.
   */
  variant?: 'default' | 'secondary' | 'quiet' | 'destructive'
  /** For moving focus to the button, as when the panel it opened closes */
  ref?: Ref<HTMLButtonElement>
}

/**
 * While `isPending` (a change under way) it takes no press but keeps the focus, and holds a hidden progress bar, which
 * React Aria adds to the name of a button labelled by `aria-label`, so a screen reader says it's working.
 */
export function Button({ variant = 'secondary', children, ...props }: ButtonProps) {
  const face = variant === 'default' ? styles.default : variant === 'quiet' ? styles.quiet : variant === 'destructive' ? styles.destructive : ''
  return (
    <AriaButton {...props} className={`${styles.button} ${face}`}>
      {(values) => (
        <>
          {typeof children === 'function' ? children(values) : children}
          {values.isPending && <ProgressBar isIndeterminate aria-label={t('common.working')} className={styles.pendingBar} />}
        </>
      )}
    </AriaButton>
  )
}
