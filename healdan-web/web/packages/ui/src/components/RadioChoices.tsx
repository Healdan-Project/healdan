// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { Label, Radio, RadioGroup } from 'react-aria-components'
import styles from './Choices.module.css'
import type { Choice } from './CheckGroup'

export interface RadioChoicesProps {
  label: string
  choices: Choice[]
  value: string
  onChange: (value: string) => void
}

/** A titled list of radio buttons: one choice among several. */
export function RadioChoices({ label, choices, value, onChange }: RadioChoicesProps) {
  return (
    <RadioGroup className={styles.group} value={value} onChange={onChange}>
      <Label className={styles.legend}>{label}</Label>
      {choices.map((choice) => (
        <Radio key={choice.value} className={styles.choice} value={choice.value}>
          <span className={`${styles.box} ${styles.round}`} aria-hidden>
            <span className={styles.dot} />
          </span>
          <span className={styles.text}>{choice.label}</span>
        </Radio>
      ))}
    </RadioGroup>
  )
}
