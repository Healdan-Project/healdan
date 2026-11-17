// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { ToggleButton, ToggleButtonGroup } from 'react-aria-components'
import type { Choice } from './CheckGroup'
import styles from './ToggleButtons.module.css'

export interface ToggleButtonsProps {
  /** The group's name for a screen reader */
  label: string
  choices: Choice[]
  /** The choice pressed now; one always is */
  value: string
  onChange: (value: string) => void
}

/**
 * A row of joined buttons, one of which is pressed: a switch between views, as the Trash's Mine · Everyone's · Removed. A
 * screen reader hears a group of radio buttons, which the arrow keys move between, as React Aria's ToggleButtonGroup does
 * for a single choice.
 */
export function ToggleButtons({ label, choices, value, onChange }: ToggleButtonsProps) {
  return (
    <ToggleButtonGroup
      aria-label={label}
      selectionMode="single"
      disallowEmptySelection
      selectedKeys={[value]}
      onSelectionChange={(keys) => {
        const next = [...keys][0]
        if (next !== undefined && next !== value) onChange(String(next))
      }}
      className={styles.group}
    >
      {choices.map((choice) => (
        <ToggleButton key={choice.value} id={choice.value} className={styles.button}>
          {choice.label}
        </ToggleButton>
      ))}
    </ToggleButtonGroup>
  )
}
