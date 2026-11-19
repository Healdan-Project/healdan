// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import styles from './GroupChip.module.css'

export interface GroupChipProps {
  /** The group's name, shown as text */
  name: string
  /** The administrators' group, whose members read every document: drawn apart, its name still saying which it is */
  admin?: boolean
}

/** A group a person is in, as a chip among their others */
export function GroupChip({ name, admin = false }: GroupChipProps) {
  return (
    <span className={styles.chip} data-admin={admin || undefined}>
      {name}
    </span>
  )
}
