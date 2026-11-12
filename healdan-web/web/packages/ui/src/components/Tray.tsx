// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useState, type ReactNode } from 'react'
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
  /** Where it went, such as "In Drafts" */
  place?: string
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
  /** Removes every finished item and keeps the running ones; no button without it */
  onClear?: () => void
  clearLabel?: string
  collapseLabel: string
  expandLabel: string
}

/**
 * A corner panel of work that goes on while the person moves about the app: each item with its state, buttons of its own,
 * and, once it's finished, Dismiss; Clear Finished takes every finished item off at once, and Collapse folds the list away
 * to its summary, so the tray needn't cover what's behind it. It is a named region, and is marked as a top layer: a modal
 * makes the rest of the page inert, and leaves out what is marked, so a screen reader still hears the summary change (the
 * tab order is the modal's, so the buttons wait until it closes). Nothing is drawn when there are no items.
 */
export function Tray({ label, summary, items, onDismiss, dismissLabel, onClear, clearLabel, collapseLabel, expandLabel }: TrayProps) {
  const [collapsed, setCollapsed] = useState(false)
  if (items.length === 0) return null
  const finished = items.some((item) => item.state === 'filed' || item.state === 'refused')
  return (
    <section aria-label={label} className={styles.tray} data-react-aria-top-layer="true">
      <div className={styles.header}>
        <p role="status" className={styles.summary}>
          {summary}
        </p>
        {onClear && finished && (
          <Button variant="quiet" onPress={onClear}>
            {clearLabel}
          </Button>
        )}
        <Button variant="quiet" aria-expanded={!collapsed} aria-label={collapsed ? expandLabel : collapseLabel} onPress={() => setCollapsed(!collapsed)}>
          <Icon name={collapsed ? 'chevronUp' : 'chevronDown'} size={14} />
        </Button>
      </div>
      {!collapsed && (
        <ul className={styles.list}>
          {items.map((item) => (
            <li key={item.id} className={styles.item} data-state={item.state}>
              <span className={styles.name}>{item.name}</span>
              <span className={styles.status}>
                <span>{item.status}</span>
                {item.place && <span className={styles.place}>{item.place}</span>}
              </span>
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
      )}
    </section>
  )
}
