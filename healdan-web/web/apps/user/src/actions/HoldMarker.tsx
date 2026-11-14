// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { components } from '@healdan/api'
import { Icon } from '@healdan/ui'
import { useMe } from '../queries'
import { holdLabel, holdState } from './checkout'
import styles from './actions.module.css'

type S = components['schemas']

/**
 * A list row's mark that its document is held: the pencil for a check-out, as Check Out's own, and the padlock for a lock.
 * It's the icon alone, which stays where it is on hover, focus and selection (changed after M6: the owner's dogfooding
 * punch list, 2026-11-14). Its name, and its tooltip, say who has it, "Checked out by Priya Nair"; the details' Check-Out
 * Info has since when and on which computer. Nothing for a free document. The cell's icons take the row's colours
 * (Table.module.css), so it meets contrast in both themes, and on a selected row.
 */
export function HoldMarker({ document }: { document: S['DocumentView'] }) {
  // Only a held row asks who the person is: a list has many rows, and most are free
  return document.hold ? <Held hold={document.hold} /> : null
}

function Held({ hold }: { hold: S['HoldView'] }) {
  const me = useMe()
  const state = holdState(hold, me.data?.id)
  const label = holdLabel(state)
  return (
    <span role="img" aria-label={label} title={label} className={styles.holdMarker}>
      <Icon name={state.kind === 'locked' ? 'lock' : 'pencil'} size={14} />
    </span>
  )
}
