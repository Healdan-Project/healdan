// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import {
  Text,
  UNSTABLE_Toast as AriaToast,
  UNSTABLE_ToastContent as ToastContent,
  UNSTABLE_ToastQueue as ToastQueue,
  UNSTABLE_ToastRegion as AriaToastRegion,
  type QueuedToast,
} from 'react-aria-components'
import { Icon } from '../icons'
import { t } from '../strings'
import { Button } from './Button'
import styles from './Toast.module.css'

interface ToastMessage {
  message: string
}

const queue = new ToastQueue<ToastMessage>({ maxVisibleToasts: 3 })

/** Shows a message that goes away by itself after 5 seconds (screen readers hear it as a status). */
export function toast(message: string): void {
  queue.add({ message }, { timeout: 5000 })
}

/** Dismisses every message at once (on sign-out, and between tests). */
export function clearToasts(): void {
  queue.clear()
}

/** One message in the region. */
export function Toast({ toast: item }: { toast: QueuedToast<ToastMessage> }) {
  return (
    <AriaToast toast={item} className={styles.toast}>
      <ToastContent>
        <Text slot="title" role="status" className={styles.message}>
          {item.content.message}
        </Text>
      </ToastContent>
      <Button slot="close" aria-label={t('toast.dismiss')}>
        <Icon name="close" />
      </Button>
    </AriaToast>
  )
}

/** Put one ToastRegion near the root of the app. */
export function ToastRegion() {
  return (
    <AriaToastRegion queue={queue} className={styles.region} aria-label={t('toast.region')}>
      {({ toast: item }) => <Toast toast={item} />}
    </AriaToastRegion>
  )
}
