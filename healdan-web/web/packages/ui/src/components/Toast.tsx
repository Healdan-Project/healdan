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
import { useSyncExternalStore } from 'react'
import { Icon } from '../icons'
import { t } from '../strings'
import { Button } from './Button'
import styles from './Toast.module.css'

interface ToastMessage {
  message: string
  /** Where the part of the message in [square brackets] goes */
  href?: string
  /** One button after the message, such as Undo; pressing it closes the toast */
  action?: ToastAction
}

export interface ToastAction {
  label: string
  onAction: () => void
}

const queue = new ToastQueue<ToastMessage>({ maxVisibleToasts: 3 })

/** Shows a message that goes away by itself after 5 seconds (screen readers hear it as a status). */
export function toast(message: string): void {
  queue.add({ message }, { timeout: 5000 })
}

/**
 * Shows a message with a link, which stays for 15 seconds so there's time to follow it. The words in [square brackets]
 * are the link, to `href`, which opens in a page of its own. The region is above a dialog and outside what it hides, so
 * a message made while Quick Look is open can be read and reached.
 */
export function toastWithLink(message: string, href: string): void {
  queue.add({ message, href }, { timeout: 15000 })
}

/**
 * Shows a message with one action, such as Undo, which stays for 10 seconds. React Aria's region pauses the timer while
 * the pointer or focus is on it. Pressing the action calls `onAction` once and closes the toast.
 */
export function toastWithAction(message: string, action: ToastAction): void {
  queue.add({ message, action }, { timeout: 10000 })
}

function Message({ message, href }: ToastMessage) {
  const match = href ? /^(.*)\[([^\]]+)\](.*)$/s.exec(message) : null
  if (!match || !href) return <>{message}</>
  return (
    <>
      {match[1]}
      <a href={href} target="_blank" rel="noopener noreferrer" className={styles.link}>
        {match[2]}
      </a>
      {match[3]}
    </>
  )
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
          <Message {...item.content} />
        </Text>
      </ToastContent>
      {item.content.action && (
        <Button
          variant="quiet"
          onPress={() => {
            queue.close(item.key)
            item.content.action?.onAction()
          }}
        >
          {item.content.action.label}
        </Button>
      )}
      <Button slot="close" aria-label={t('toast.dismiss')}>
        <Icon name="close" />
      </Button>
    </AriaToast>
  )
}

function subscribe(onChange: () => void): () => void {
  return queue.subscribe(onChange)
}

function hasToasts(): boolean {
  return queue.visibleToasts.length > 0
}

/**
 * Put one ToastRegion near the root of the app. React Aria draws nothing while there is no toast, and registers the
 * region as a landmark (F6) only if it has an element when it mounts, so the region is mounted afresh each time the
 * first toast appears: without that, F6 would never reach a toast made after the page loaded.
 */
export function ToastRegion() {
  const showing = useSyncExternalStore(subscribe, hasToasts, hasToasts)
  return (
    <AriaToastRegion key={showing ? 'showing' : 'empty'} queue={queue} className={styles.region} aria-label={t('toast.region')}>
      {({ toast: item }) => <Toast toast={item} />}
    </AriaToastRegion>
  )
}
