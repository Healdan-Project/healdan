// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { Button as AriaButton, type ButtonProps as AriaButtonProps } from 'react-aria-components'
import styles from './Button.module.css'

export interface ButtonProps extends Omit<AriaButtonProps, 'className' | 'style'> {
  /** The default button is the accent one; secondary has a field-coloured face; quiet has none, for an icon in a row. */
  variant?: 'default' | 'secondary' | 'quiet'
}

export function Button({ variant = 'secondary', ...props }: ButtonProps) {
  const face = variant === 'default' ? styles.default : variant === 'quiet' ? styles.quiet : ''
  return <AriaButton {...props} className={`${styles.button} ${face}`} />
}
