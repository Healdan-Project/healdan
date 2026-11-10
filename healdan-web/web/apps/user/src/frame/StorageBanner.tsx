// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQueryClient } from '@tanstack/react-query'
import { useSyncExternalStore } from 'react'
import { t } from '@healdan/ui'
import { isStorageUnreachable } from './errors'
import styles from './StorageBanner.module.css'

// Shown while any request on screen is failing because the files' storage can't be reached.
export function StorageBanner() {
  const cache = useQueryClient().getQueryCache()
  const down = useSyncExternalStore(
    (listener) => cache.subscribe(listener),
    () => cache.getAll().some((q) => q.getObserversCount() > 0 && q.state.status === 'error' && isStorageUnreachable(q.state.error)),
  )
  return down ? (
    <div role="alert" className={styles.banner}>
      {t('storage.unreachable')}
    </div>
  ) : null
}
