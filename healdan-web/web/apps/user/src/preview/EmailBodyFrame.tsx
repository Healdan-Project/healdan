// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { t } from '@healdan/ui'
import styles from './email.module.css'

/**
 * Exactly these two, and never `allow-scripts` or `allow-same-origin` (spec section 9.4): no script runs in the body, and the
 * frame has an origin of its own, so it can't reach the app. A link opens in a window of its own, which the frame's
 * sandbox doesn't follow.
 */
const SANDBOX = 'allow-popups allow-popups-to-escape-sandbox'

/**
 * An email's body: the server's page, cleaned and under its own policy, in a frame. `src` is the page's address, which
 * the server answers as a plain same-origin navigation, so a different address (with the pictures) reloads it.
 */
export function EmailBodyFrame({ src }: { src: string }) {
  return <iframe title={t('email.body')} src={src} sandbox={SANDBOX} referrerPolicy="no-referrer" className={styles.frame} />
}
