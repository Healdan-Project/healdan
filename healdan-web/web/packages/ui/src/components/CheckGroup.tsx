// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { Checkbox, CheckboxGroup, Label } from 'react-aria-components'
import { Icon } from '../icons'
import styles from './Choices.module.css'

export interface Choice {
  value: string
  label: string
  /** How many documents choosing it would leave; none says nothing */
  count?: number
  /** What a screen reader says for the option, when the label and count alone read badly */
  name?: string
}

export interface CheckGroupProps {
  label: string
  choices: Choice[]
  value: string[]
  onChange: (value: string[]) => void
}

/** A titled list of tick boxes, each with its count at the end. */
export function CheckGroup({ label, choices, value, onChange }: CheckGroupProps) {
  return (
    <CheckboxGroup className={styles.group} value={value} onChange={onChange}>
      <Label className={styles.legend}>{label}</Label>
      {choices.map((choice) => (
        <Checkbox key={choice.value} className={styles.choice} value={choice.value} aria-label={choice.name}>
          <span className={styles.box} aria-hidden>
            <Icon name="check" size={12} />
          </span>
          <span className={styles.text}>{choice.label}</span>
          {choice.count !== undefined && <span className={styles.count} data-count>{choice.count}</span>}
        </Checkbox>
      ))}
    </CheckboxGroup>
  )
}
