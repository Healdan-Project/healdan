// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { ReactNode } from 'react'
import {
  Menu as AriaMenu,
  MenuItem as AriaMenuItem,
  MenuTrigger,
  Popover,
  Separator,
  type MenuItemProps as AriaMenuItemProps,
} from 'react-aria-components'
import { Button, type ButtonProps } from './Button'
import styles from './Menu.module.css'

export interface MenuProps {
  /** What the button that opens the menu shows. */
  label: ReactNode
  /** The button's accessible name, when its label is only an icon. */
  'aria-label'?: string
  /** The button's face (Button's variants) */
  variant?: ButtonProps['variant']
  /** The button doesn't open the menu */
  isDisabled?: boolean
  children: ReactNode
}

export function Menu({ label, children, ...rest }: MenuProps) {
  return (
    <MenuTrigger>
      <Button {...rest}>{label}</Button>
      <Popover className={styles.popover}>
        <AriaMenu className={styles.menu}>{children}</AriaMenu>
      </Popover>
    </MenuTrigger>
  )
}

export function MenuItem(props: Omit<AriaMenuItemProps, 'className' | 'style'>) {
  return <AriaMenuItem {...props} className={styles.item} />
}

/** A line between a menu's groups of items */
export function MenuSeparator() {
  return <Separator className={styles.separator} />
}
