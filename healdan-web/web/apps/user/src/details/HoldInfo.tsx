// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { components } from '@healdan/api'
import { formatDateTime, t } from '@healdan/ui'
import { holdState } from '../actions/checkout'
import { useMe } from '../queries'
import { Section } from './Section'
import styles from './details.module.css'

type S = components['schemas']

interface Row {
  label: string
  value: string
}

/**
 * A held document's Check-Out Info, or Lock Info, above the tabs (changed after M6: the owner's dogfooding punch list,
 * 2026-11-14): who has it, since when, and the app and computer a check-out is held on. The API gives those two
 * (`hold.heldOn`) only to the person who holds the check-out and to administrators, and a row it doesn't give is left
 * out, so anyone else sees User and Check-Out Time. Nothing for a free document.
 */
export function HoldInfo({ document }: { document: S['DocumentView'] }) {
  const me = useMe()
  const hold = document.hold
  if (!hold) return null
  const state = holdState(hold, me.data?.id)
  const mine = state.kind === 'locked' ? state.mine : state.kind === 'yours' || state.kind === 'yourDevice'
  const lock = hold.kind === 'lock'

  const rows: Row[] = []
  if (hold.by) {
    const name = hold.by.name
    rows.push({ label: t('hold.user'), value: mine ? (name ? t('hold.you', { name }) : t('hold.youUnnamed')) : (name ?? t('details.unnamedUser')) })
  }
  if (hold.since) rows.push({ label: t(lock ? 'hold.lockTime' : 'hold.checkoutTime'), value: formatDateTime(hold.since) })
  if (hold.heldOn?.app) rows.push({ label: t('hold.application'), value: hold.heldOn.app })
  if (hold.heldOn?.name) rows.push({ label: t('hold.computer'), value: hold.heldOn.name })

  return (
    <Section title={t(lock ? 'hold.lockInfo' : 'hold.checkoutInfo')} boxed>
      {rows.length > 0 && (
        <dl className={`${styles.properties} ${styles.holdInfo}`}>
          {rows.map((row) => (
            <div key={row.label} className={styles.property}>
              <dt>{row.label}</dt>
              <dd>{row.value}</dd>
            </div>
          ))}
        </dl>
      )}
    </Section>
  )
}
