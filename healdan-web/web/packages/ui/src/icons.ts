// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

// Only the icons the screens use, so the bundle holds only those (tabler's barrel is tree-shaken by name).
import { createElement, type SVGAttributes } from 'react'
import {
  IconArrowDown,
  IconArrowUp,
  IconBriefcase,
  IconBuilding,
  IconCheck,
  IconChevronDown,
  IconChevronRight,
  IconDots,
  IconFileSpreadsheet,
  IconFileText,
  IconFileTypeDocx,
  IconFileTypePdf,
  IconFolder,
  IconFolderSearch,
  IconHelpCircle,
  IconMail,
  IconPhoto,
  IconPlus,
  IconPresentation,
  IconSearch,
  IconStar,
  IconStarFilled,
  IconX,
} from '@tabler/icons-react'

export const icons = {
  arrowDown: IconArrowDown,
  arrowUp: IconArrowUp,
  briefcase: IconBriefcase,
  building: IconBuilding,
  check: IconCheck,
  chevronDown: IconChevronDown,
  chevronRight: IconChevronRight,
  close: IconX,
  document: IconFileText,
  dots: IconDots,
  email: IconMail,
  filePdf: IconFileTypePdf,
  fileWord: IconFileTypeDocx,
  folder: IconFolder,
  folderSearch: IconFolderSearch,
  help: IconHelpCircle,
  picture: IconPhoto,
  plus: IconPlus,
  presentation: IconPresentation,
  search: IconSearch,
  spreadsheet: IconFileSpreadsheet,
  star: IconStar,
  starFilled: IconStarFilled,
}

export type IconName = keyof typeof icons

// Icons are decoration: the control around one carries the label.
export function Icon({ name, size = 16, ...rest }: { name: IconName; size?: number } & SVGAttributes<SVGElement>) {
  return createElement(icons[name], { size, stroke: 1.75, 'aria-hidden': true, ...rest })
}
