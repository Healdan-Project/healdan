// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

// Only the icons the screens use, so the bundle holds only those (tabler's barrel is tree-shaken by name).
import { createElement, type SVGAttributes } from 'react'
import { IconCheck, IconChevronDown, IconX } from '@tabler/icons-react'

export const icons = { check: IconCheck, chevronDown: IconChevronDown, close: IconX }

export type IconName = keyof typeof icons

// Icons are decoration: the control around one carries the label.
export function Icon({ name, size = 16, ...rest }: { name: IconName; size?: number } & SVGAttributes<SVGElement>) {
  return createElement(icons[name], { size, stroke: 1.75, 'aria-hidden': true, ...rest })
}
