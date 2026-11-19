// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { t } from '@healdan/ui'
import styles from './SignedOut.module.css'

/**
 * What the app shows instead of itself at an address that isn't secure (R91): it can't work there, so it says why and
 * offers the Classic Interface, which can. It is drawn before anything asks the server, and in the page's own colours.
 */
export function InsecureAddress() {
  return (
    <main className={styles.panel}>
      <h1 className={styles.message}>{t('insecure.message')}</h1>
      <p className={styles.detail}>{t('insecure.detail')}</p>
      <a className={styles.signIn} href="/frontend.jsp">
        {t('person.classic')}
      </a>
    </main>
  )
}
