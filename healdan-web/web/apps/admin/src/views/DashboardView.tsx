// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQueryClient } from '@tanstack/react-query'
import { Button, Icon, formatDate } from '@healdan/ui'
import { Attention } from '../dashboard/Attention'
import { HealthTiles } from '../dashboard/HealthTiles'
import { Usage } from '../dashboard/Usage'
import { adminKeys, useDashboard, useUsage } from '../queries'
import { t } from '../strings'
import styles from '../dashboard/Dashboard.module.css'

/**
 * The Dashboard, at /admin/ (spec 8.1): Needs Attention first, then Server Health and Usage. Needs Attention and Server
 * Health are one request and Usage another, each asked again every 30 seconds while the page is visible and when the
 * person comes back to it, and each part shows its own error, so one failing never blanks the others.
 */
export function DashboardView() {
  const queryClient = useQueryClient()
  const dashboard = useDashboard()
  const usage = useUsage()
  return (
    <div className={styles.view}>
      <div className={styles.head}>
        <h1 className={styles.title}>{t('admin.dashboard.title')}</h1>
        {dashboard.isSuccess && <span className={styles.updated}>{t('admin.dashboard.updated', { when: formatDate(new Date(dashboard.dataUpdatedAt).toISOString()) })}</span>}
        <span className={styles.refresh}>
          <Button onPress={() => void queryClient.invalidateQueries({ queryKey: adminKeys.dashboard })}>
            <Icon name="refresh" />
            {t('admin.dashboard.refresh')}
          </Button>
        </span>
      </div>
      <Attention dashboard={dashboard} />
      <HealthTiles dashboard={dashboard} />
      <Usage usage={usage} />
    </div>
  )
}
