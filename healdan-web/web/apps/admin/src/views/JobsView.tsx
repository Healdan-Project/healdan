// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { t } from '../strings'
import styles from './views.module.css'

/** Background Jobs, at /admin/jobs (spec 8.8): Healdan's jobs and LogicalDOC's tasks, in one list */
export function JobsView() {
  return (
    <div className={styles.view}>
      <h1 className={styles.title}>{t('admin.jobs.title')}</h1>
    </div>
  )
}
