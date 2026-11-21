// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { ReactNode } from 'react'
import { t } from '../strings'
import type { AdminStringKey } from '../strings/en-US'
import styles from '../clients/Clients.module.css'

/**
 * One of a page's facts, its term over what it says, as every page of the app draws them: a client's, a matter's, a Store's
 * and a backend's. It goes in a `<dl>` with the `facts` style of Clients.module.css.
 */
export function Fact({ term, children }: { term: AdminStringKey; children: ReactNode }) {
  return (
    <div className={styles.fact}>
      <dt>{t(term)}</dt>
      <dd>{children}</dd>
    </div>
  )
}
