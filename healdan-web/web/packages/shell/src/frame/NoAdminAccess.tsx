// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { t } from '@healdan/ui'
import styles from './SignedOut.module.css'

/**
 * What someone who isn't an administrator sees at Healdan Admin's addresses (admin spec 6.1): no way in, and the way
 * to the User app. The server answers them there with the User app's own page, which draws this before it asks the
 * server anything, and the Admin app draws it when the API says the same.
 */
export function NoAdminAccess() {
  return (
    <main className={styles.panel}>
      <h1 className={styles.message}>{t('noAdminAccess.message')}</h1>
      <a className={styles.signIn} href="/app/">
        {t('noAdminAccess.open')}
      </a>
    </main>
  )
}
