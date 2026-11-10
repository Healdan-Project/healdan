// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { Button, t } from '@healdan/ui'
import { isStorageUnreachable, messageFor } from './errors'
import styles from './ErrorPanel.module.css'

// A view that couldn't load. A storage outage already has its banner above the view, so only Try Again is here then.
export function ErrorPanel({ error, onRetry }: { error: unknown; onRetry: () => void }) {
  return (
    <div className={styles.panel}>
      {!isStorageUnreachable(error) && <p className={styles.message}>{messageFor(error)}</p>}
      <Button onPress={onRetry}>{t('common.tryAgain')}</Button>
    </div>
  )
}
