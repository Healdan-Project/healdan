// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { ReactNode } from 'react'
import { Icon } from '../icons'
import { Button } from './Button'
import styles from './Tray.module.css'

export type TrayState = 'waiting' | 'uploading' | 'filed' | 'refused'

export interface TrayItem {
  id: number | string
  name: string
  state: TrayState
  /** What the state says, in words: "Filed as # 10500", or why it was refused */
  status: string
  /** Buttons for the item, such as Edit */
  actions?: ReactNode
}

export interface TrayProps {
  /** The region's name for a screen reader */
  label: string
  /** A sentence of how far it is, announced as a status when it changes */
  summary?: string
  items: TrayItem[]
  /** Dismisses a finished item (filed or refused) */
  onDismiss: (id: number | string) => void
  dismissLabel: (name: string) => string
}

/**
 * A corner panel of work that goes on while the person moves about the app: each item with its state, buttons of its own,
 * and, once it's finished, Dismiss. It is a named region in the page's tab order, and is marked as a top layer, so a
 * modal's hiding of the rest of the page leaves it readable by a screen reader. Nothing is drawn when there are no items.
 */
export function Tray({ label, summary, items, onDismiss, dismissLabel }: TrayProps) {
  if (items.length === 0) return null
  return (
    <section aria-label={label} className={styles.tray} data-react-aria-top-layer="true">
      <p role="status" className={styles.summary}>
        {summary}
      </p>
      <ul className={styles.list}>
        {items.map((item) => (
          <li key={item.id} className={styles.item} data-state={item.state}>
            <span className={styles.name}>{item.name}</span>
            <span className={styles.status}>{item.status}</span>
            <span className={styles.actions}>
              {item.actions}
              {(item.state === 'filed' || item.state === 'refused') && (
                <Button variant="quiet" aria-label={dismissLabel(item.name)} onPress={() => onDismiss(item.id)}>
                  <Icon name="close" size={14} />
                </Button>
              )}
            </span>
          </li>
        ))}
      </ul>
    </section>
  )
}
