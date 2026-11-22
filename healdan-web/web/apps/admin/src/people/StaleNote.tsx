// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { Button } from '@healdan/ui'
import { t } from '../strings'
import styles from './People.module.css'

/**
 * A page, or part of one, that keeps the last answer it had while a new read fails (P4-R15): a short note that it
 * couldn't refresh, with Try Again, in place of the error that would take the page, and any dialog open on it, away.
 * There before it has words, so a screen reader says them as they come.
 */
export function StaleNote({ failed, onRetry }: { failed: boolean; onRetry: () => void }) {
  return (
    <div role="status">
      {failed && (
        <p className={styles.stale}>
          <span>{t('admin.people.stale')}</span>
          <Button onPress={onRetry}>{t('common.tryAgain')}</Button>
        </p>
      )}
    </div>
  )
}
