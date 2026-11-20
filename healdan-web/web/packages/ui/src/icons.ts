// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

// Only the icons the screens use, so the bundle holds only those (tabler's barrel is tree-shaken by name).
import { createElement, type SVGAttributes } from 'react'
import {
  IconArrowDown,
  IconArrowUp,
  IconArrowUpRight,
  IconBriefcase,
  IconBuilding,
  IconCalendar,
  IconCheck,
  IconChevronDown,
  IconChevronLeft,
  IconChevronRight,
  IconChevronUp,
  IconColumns3,
  IconDots,
  IconDownload,
  IconExternalLink,
  IconEye,
  IconFileSpreadsheet,
  IconFileText,
  IconFileTypeCsv,
  IconFileTypeDoc,
  IconFileTypeDocx,
  IconFileTypePdf,
  IconFileTypePpt,
  IconFileTypeXls,
  IconFilter,
  IconFolder,
  IconFolderPlus,
  IconFolderSearch,
  IconHelpCircle,
  IconLayoutDashboard,
  IconLayoutSidebarRight,
  IconAlertTriangle,
  IconLock,
  IconLockOpen,
  IconMenu2,
  IconMail,
  IconPencil,
  IconPhoto,
  IconPlus,
  IconPresentation,
  IconRefresh,
  IconSearch,
  IconStar,
  IconStarFilled,
  IconTrash,
  IconUpload,
  IconUser,
  IconX,
  IconZoomIn,
  IconZoomOut,
  type IconProps,
} from '@tabler/icons-react'
import styles from './icons.module.css'

export const icons = {
  arrowDown: IconArrowDown,
  arrowUp: IconArrowUp,
  arrowUpRight: IconArrowUpRight,
  briefcase: IconBriefcase,
  building: IconBuilding,
  calendar: IconCalendar,
  check: IconCheck,
  chevronDown: IconChevronDown,
  chevronLeft: IconChevronLeft,
  chevronRight: IconChevronRight,
  chevronUp: IconChevronUp,
  close: IconX,
  columns: IconColumns3,
  dashboard: IconLayoutDashboard,
  document: IconFileText,
  dots: IconDots,
  download: IconDownload,
  email: IconMail,
  external: IconExternalLink,
  eye: IconEye,
  fileCsv: IconFileTypeCsv,
  fileDoc: IconFileTypeDoc,
  fileDocx: IconFileTypeDocx,
  filePdf: IconFileTypePdf,
  filePpt: IconFileTypePpt,
  fileXls: IconFileTypeXls,
  filter: IconFilter,
  folder: IconFolder,
  folderPlus: IconFolderPlus,
  folderSearch: IconFolderSearch,
  help: IconHelpCircle,
  dock: IconLayoutSidebarRight,
  lock: IconLock,
  lockOpen: IconLockOpen,
  alertTriangle: IconAlertTriangle,
  menu: IconMenu2,
  pencil: IconPencil,
  picture: IconPhoto,
  plus: IconPlus,
  presentation: IconPresentation,
  refresh: IconRefresh,
  search: IconSearch,
  spreadsheet: IconFileSpreadsheet,
  star: IconStar,
  starFilled: IconStarFilled,
  trash: IconTrash,
  upload: IconUpload,
  user: IconUser,
  zoomIn: IconZoomIn,
  zoomOut: IconZoomOut,
}

export type IconName = keyof typeof icons

/**
 * The kinds of item an icon can stand for, each drawn in its own colour (`--hd-kind-*` in tokens.css). An icon that
 * stands for no kind, a plain document or an action, keeps the colour of what it sits in.
 */
export const iconKinds = ['wordProcessing', 'spreadsheet', 'presentation', 'pdf', 'email', 'picture', 'folder', 'matter', 'client'] as const
export type IconKind = (typeof iconKinds)[number]

// Icons are decoration: the control around one carries the label. Every icon keeps its size beside text that is cut
// short, and a kind's icon carries `data-kind`, by which a list tells it from the icons it mutes (icons.module.css).
export function Icon({ name, size = 16, kind, className, ...rest }: { name: IconName; size?: number; kind?: IconKind } & SVGAttributes<SVGElement>) {
  const props: IconProps & { 'data-kind'?: IconKind } = { size, stroke: 1.75, 'aria-hidden': true, 'data-kind': kind, className: [styles.icon, kind && styles[kind], className].filter(Boolean).join(' '), ...rest }
  return createElement(icons[name], props)
}
