// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { Button, Dialog, t } from '@healdan/ui'
import styles from './organising.module.css'

export interface RefusalsProps {
  title: string
  /** Each document that was refused, and why */
  lines: { key: number; name: string; reason: string }[]
  onClose: () => void
}

/** What a move or a delete of several documents didn't do, one line for each refused document. */
export function Refusals({ title, lines, onClose }: RefusalsProps) {
  return (
    <Dialog title={title} isOpen onOpenChange={(open) => !open && onClose()}>
      {(close) => (
        <>
          <ul className={styles.names}>
            {lines.map((line) => (
              <li key={line.key}>{t('organising.refused', { name: line.name, reason: line.reason })}</li>
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
