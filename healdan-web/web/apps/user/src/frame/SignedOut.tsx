// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { t } from '@healdan/ui'
import styles from './SignedOut.module.css'

// Sign In is a link to this very address: a full page load, which reaches AppServlet, which remembers it and sends the
// person to the sign-in page, and back here afterwards.
export function SignedOut() {
  const here = window.location.pathname + window.location.search
  return (
    <main className={styles.panel}>
      <h1 className={styles.message}>{t('signedOut.message')}</h1>
      <a className={styles.signIn} href={here}>
        {t('signedOut.signIn')}
      </a>
    </main>
  )
}
