// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { Link } from '@tanstack/react-router'
import { useState } from 'react'
import type { adminComponents } from '@healdan/api'
import { routes } from '../addresses'
import { t } from '../strings'
import { KindsFilter } from './AuditFilters'
import { AuditList } from './AuditList'
import { auditKinds, matterKinds } from './text'
import styles from './Audit.module.css'

type A = adminComponents['schemas']

export interface ActivityPanelProps {
  /** Whose Activity it is: a matter's, by the matter's id, or a person's, by their user id */
  fixed: { matterId: number } | { userId: number }
  /** The matter's or the person's name, which names the list */
  name: string
}

/**
 * The Activity tab of a matter's page and a person's (spec 8.3, 8.7; P5-R24): the Audit Trail's list with the matter or
 * the person fixed, the kinds its only filter (a matter's only those a matter's rows can be), and no export, which the
 * whole Audit Trail, a link away with the same filters, has. The kinds are the tab's own, not the page's address.
 */
export function ActivityPanel({ fixed, name }: ActivityPanelProps) {
  const [kinds, setKinds] = useState<A['AuditKind'][] | undefined>(undefined)
  const ofMatter = 'matterId' in fixed
  return (
    <div className={styles.activity}>
      <div className={styles.activityHead}>
        <KindsFilter offered={ofMatter ? matterKinds : auditKinds} value={kinds ?? []} onChange={setKinds} />
        <Link to={routes.audit} search={ofMatter ? { kind: kinds, matter: fixed.matterId } : { kind: kinds, user: fixed.userId }} className={styles.link}>
          {t('admin.activity.inAuditTrail')}
        </Link>
      </div>
      <AuditList query={{ ...fixed, kind: kinds }} filtered={kinds !== undefined} label={t('admin.activity.label', { name })} />
    </div>
  )
}
