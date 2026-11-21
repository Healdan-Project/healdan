// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { Fragment, useId } from 'react'
import { Label, Radio, RadioGroup } from 'react-aria-components'
import styles from './Choices.module.css'
import type { Choice } from './CheckGroup'

/** A choice of the list: one that can't be made is greyed out, and either can say more of itself after its name */
export interface RadioChoice extends Choice {
  /** It's shown, greyed out, and can't be chosen */
  disabled?: boolean
  /** A line under it, which a screen reader reads after its name, such as why it can't be chosen */
  description?: string
}

export interface RadioChoicesProps {
  label: string
  choices: RadioChoice[]
  value: string
  onChange: (value: string) => void
}

/** A titled list of radio buttons: one choice among several. */
export function RadioChoices({ label, choices, value, onChange }: RadioChoicesProps) {
  const id = useId()
  return (
    <RadioGroup className={styles.group} value={value} onChange={onChange}>
      <Label className={styles.legend}>{label}</Label>
      {choices.map((choice, index) => {
        const describedBy = choice.description === undefined ? undefined : `${id}-${index}`
        return (
          <Fragment key={choice.value}>
            <Radio className={styles.choice} value={choice.value} isDisabled={choice.disabled} aria-describedby={describedBy}>
              <span className={`${styles.box} ${styles.round}`} aria-hidden>
                <span className={styles.dot} />
              </span>
              <span className={styles.text}>{choice.label}</span>
            </Radio>
            {describedBy !== undefined && (
              <span id={describedBy} className={styles.description}>
                {choice.description}
              </span>
            )}
          </Fragment>
        )
      })}
    </RadioGroup>
  )
}
