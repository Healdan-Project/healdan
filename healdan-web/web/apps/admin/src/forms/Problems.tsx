// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { ReactNode } from 'react'
import { Icon } from '@healdan/ui'
import styles from './Problems.module.css'

/** A problem with what can put it right */
export interface Problem {
  /** Unique among the list's problems */
  key: string
  /** What's wrong, drawn as text */
  sentence: string
  /** The sentence's id, so what it's about, such as a greyed button, can be described by it (`aria-describedby`) */
  id?: string
  /** Under the sentence: what can put it right, such as a button, and what that was refused with */
  action?: ReactNode
}

export interface ProblemsProps {
  /** A line above them that says what found them: "Test found 2 problems:" */
  lead?: string
  /** Each problem: its sentence alone, or a Problem */
  problems: readonly (string | Problem)[]
  /** Each in a box of its own with the caution border, as among a form's fields, where the list stands by itself */
  framed?: boolean
}

/**
 * Problems found before a button is pressed, or by a test (spec 7.2: problems are said before the button): each sentence,
 * drawn as text, after the warning sign, with what can put it right under it, and the line that says what found them
 * above the list. Nothing is drawn while there's none.
 */
export function Problems({ lead, problems, framed = false }: ProblemsProps) {
  if (problems.length === 0) return null
  const items = problems.map((problem, index): Problem => (typeof problem === 'string' ? { key: String(index), sentence: problem } : problem))
  return (
    <div className={styles.found}>
      {lead !== undefined && <p className={styles.line}>{lead}</p>}
      <ul className={framed ? `${styles.problems} ${styles.spaced}` : styles.problems}>
        {items.map((problem) => (
          <li key={problem.key} className={framed ? `${styles.problem} ${styles.framed}` : styles.problem}>
            <Icon name="alertTriangle" className={styles.sign} />
            <div className={styles.text}>
              <p id={problem.id} className={styles.line}>
                {problem.sentence}
              </p>
              {problem.action}
            </div>
          </li>
        ))}
      </ul>
    </div>
  )
}
