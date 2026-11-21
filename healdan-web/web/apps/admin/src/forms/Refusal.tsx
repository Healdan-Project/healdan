// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { ReactNode } from 'react'
import { useRefusalFor } from '../strings/refusals'
import styles from './Form.module.css'

export interface RefusalProps {
  /**
   * What was refused, or failed: said in the rule's own sentence, or the catalogue's words (useRefusalFor). Nothing is
   * drawn while it's undefined.
   */
  failure?: unknown
  /** The sentence itself, in place of a failure's, as when the browser refused a copy; nothing is drawn for none */
  children?: ReactNode
}

/**
 * What was refused, or couldn't be done, said where it was tried, drawn as text: an alert, so a screen reader says it as
 * it comes. A form whose refusal names its fields says that beside them instead (`fieldErrors`), and leaves it out here.
 */
export function Refusal({ failure, children }: RefusalProps) {
  const refusalFor = useRefusalFor()
  const said = children ?? (failure === undefined ? undefined : refusalFor(failure))
  if (said === undefined || said === null || said === false || said === '') return null
  return (
    <p className={styles.refusal} role="alert">
      {said}
    </p>
  )
}
