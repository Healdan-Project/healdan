// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { components } from '@healdan/api'
import { Icon } from '@healdan/ui'
import { useMe } from '../queries'
import { holdLabel, holdState } from './checkout'
import styles from './actions.module.css'

/**
 * A list row's mark that its document is held: the pencil for a check-out, as Check Out's own, and the padlock for a lock.
 * Its name, and its tooltip, say who has it and since when, as the details do, without where it's released; nothing for a
 * free document. The cell's icons take the row's colours (Table.module.css), so it meets contrast in both themes, and on a
 * selected row.
 */
export function HoldMarker({ document }: { document: components['schemas']['DocumentView'] }) {
  const me = useMe()
  if (!document.hold) return null
  const state = holdState(document.hold, me.data?.id)
  const label = holdLabel(state)
  return (
    <span role="img" aria-label={label} title={label} className={styles.holdMarker}>
      <Icon name={state.kind === 'locked' ? 'lock' : 'pencil'} size={14} />
    </span>
  )
}
