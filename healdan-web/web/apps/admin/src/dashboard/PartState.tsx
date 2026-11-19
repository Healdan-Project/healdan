// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { ReactNode } from 'react'
import { ErrorPanel } from '@healdan/shell'
import { t } from '../strings'
import styles from './Dashboard.module.css'

/**
 * A part of the Dashboard as its answer stands: Loading while it's on its way, its own error with Try Again when it
 * failed, which leaves the other parts as they are (spec 8.1), else what it shows. The error is the catalogue's
 * sentence, never the server's.
 */
export function PartState({ query, children }: { query: { isPending: boolean; isError: boolean; error: unknown; refetch: () => unknown }; children: ReactNode }) {
  if (query.isError) return <ErrorPanel error={query.error} onRetry={() => void query.refetch()} />
  if (query.isPending) return <p className={styles.waiting}>{t('common.loading')}</p>
  return <>{children}</>
}
