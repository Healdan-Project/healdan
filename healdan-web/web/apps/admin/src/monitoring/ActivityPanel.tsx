// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { Link } from '@tanstack/react-router'
import { useState } from 'react'
import type { adminComponents } from '@healdan/api'
import { Button, Icon } from '@healdan/ui'
import { routes } from '../addresses'
import { t } from '../strings'
import { DaysFilter, KindsFilter } from './AuditFilters'
import { AuditList } from './AuditList'
import { useAuditRefresh } from './queries'
import { auditKinds, matterKinds } from './text'
import styles from './Audit.module.css'

type A = adminComponents['schemas']

export interface ActivityPanelProps {
  /** Whose Activity it is: a matter's, by the matter's id, or a person's, by their user id */
  fixed: { matterId: number } | { userId: number }
  /** The matter's or the person's name, which names the list */
  name: string
}

/** The tab's own filters: the kinds and the days */
interface ActivityFilters {
  kind?: A['AuditKind'][]
  from?: string
  to?: string
}

/**
 * The Activity tab of a matter's page and a person's (spec 8.3, 8.7; P5-R24): the Audit Trail's list with the matter or
 * the person fixed, its filters the kinds (a matter's only those a matter's rows can be) and the days (P5-R33), and no
 * export, which the whole Audit Trail, a link away with the same filters, has. Refresh asks for the list again. The
 * filters are the tab's own, not the page's address.
 */
export function ActivityPanel({ fixed, name }: ActivityPanelProps) {
  const [filters, setFilters] = useState<ActivityFilters>({})
  const ofMatter = 'matterId' in fixed
  const query = { ...fixed, ...filters }
  const { refresh, refreshing } = useAuditRefresh(query)
  const change = (next: ActivityFilters) => setFilters((now) => ({ ...now, ...next }))
  const days = { from: filters.from, to: filters.to }
  return (
    <div className={styles.activity}>
      <div className={styles.activityHead}>
        <KindsFilter offered={ofMatter ? matterKinds : auditKinds} value={filters.kind ?? []} onChange={(kind) => change({ kind })} />
        <div className={styles.activityLinks}>
          <Button isPending={refreshing} onPress={refresh}>
            <span className={styles.label}>
              <Icon name="refresh" size={14} />
              {t('admin.audit.refresh')}
            </span>
          </Button>
          <Link to={routes.audit} search={ofMatter ? { kind: filters.kind, matter: fixed.matterId, ...days } : { kind: filters.kind, user: fixed.userId, ...days }} className={styles.link}>
            {t('admin.activity.inAuditTrail')}
          </Link>
        </div>
        <div className={styles.activityDays}>
          <DaysFilter from={filters.from} to={filters.to} onChange={change} />
        </div>
      </div>
      <AuditList query={query} filtered={Object.values(filters).some((value) => value !== undefined)} label={t('admin.activity.label', { name })} />
    </div>
  )
}
