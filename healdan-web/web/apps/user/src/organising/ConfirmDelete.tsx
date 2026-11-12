// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { Button, Dialog, plural, t } from '@healdan/ui'
import type { Movable } from '../actions/batch'
import styles from './organising.module.css'

export interface ConfirmDeleteProps {
  documents: readonly Movable[]
  onConfirm: () => void
  onCancel: () => void
}

/**
 * "Move 2 documents to the Trash?", naming each (spec 6.7). The Trash is the Classic Interface's, and the dialog says so.
 * Enter confirms (the documents can be restored), Esc cancels.
 */
export function ConfirmDelete({ documents, onConfirm, onCancel }: ConfirmDeleteProps) {
  return (
    <Dialog title={plural('deleteTitle', documents.length)} isOpen onOpenChange={(open) => !open && onCancel()}>
      {(close) => (
        <>
          <ul className={styles.names}>
            {documents.map((document) => (
              <li key={document.docNumber}>{document.name}</li>
            ))}
          </ul>
          <p>{t('delete.trashNote')}</p>
          <div className={styles.actions}>
            <Button
              variant="default"
              autoFocus
              onPress={() => {
                close()
                onConfirm()
              }}
            >
              {t('delete.confirm')}
            </Button>
            <Button onPress={close}>{t('common.cancel')}</Button>
          </div>
        </>
      )}
    </Dialog>
  )
}
