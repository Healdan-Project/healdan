// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { Button, Dialog, t } from '@healdan/ui'
import type { RefusalLine } from '../actions/batch'
import styles from './organising.module.css'

export interface RefusalsProps {
  title: string
  /** Each item that was refused, and why; a folder's check-outs are listed under it */
  lines: RefusalLine[]
  onClose: () => void
}

/** What a change to several items didn't do: a move, a delete or an Undo, one line for each refused item. */
export function Refusals({ title, lines, onClose }: RefusalsProps) {
  return (
    <Dialog title={title} isOpen onOpenChange={(open) => !open && onClose()}>
      {(close) => (
        <>
          <ul className={styles.names}>
            {lines.map((line) => (
              <li key={line.key}>
                {t('organising.refused', { name: line.name, reason: line.reason })}
                {line.details && line.details.length > 0 && (
                  <ul className={styles.held}>
                    {line.details.map((detail, i) => (
                      <li key={i}>{detail}</li>
                    ))}
                  </ul>
                )}
                {line.after && <p className={styles.after}>{line.after}</p>}
              </li>
            ))}
          </ul>
          <div className={styles.actions}>
            <Button variant="default" autoFocus onPress={close}>
              {t('organising.close')}
            </Button>
          </div>
        </>
      )}
    </Dialog>
  )
}
