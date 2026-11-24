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
import { Icon, type IconName } from '../icons'
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

export function MenuItem({ icon, children, textValue, ...props }: Omit<AriaMenuItemProps, 'className' | 'style'> & { icon?: IconName }) {
  if (!icon) return <AriaMenuItem {...props} className={styles.item} textValue={textValue}>{children}</AriaMenuItem>

  const withIcon = (label: ReactNode) => <><Icon name={icon} className={styles.itemIcon} />{label}</>
  return (
    <AriaMenuItem {...props} className={styles.item} textValue={textValue ?? (typeof children === 'string' ? children : undefined)}>
      {typeof children === 'function' ? (values) => withIcon(children(values)) : withIcon(children)}
    </AriaMenuItem>
  )
}

/** A line between a menu's groups of items */
export function MenuSeparator() {
  return <Separator className={styles.separator} />
}
