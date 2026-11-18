// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { components } from '@healdan/api'
import { Button, Dialog, plural, t } from '@healdan/ui'
import { matterParts } from '../matterDisplay'
import styles from './organising.module.css'

export interface CrossMatterProps {
  /** The folder leaving its matter */
  name: string
  /** The matter it goes into, as people read it: `1003.2 – Licence Review` */
  matter: string
  /** What the folder holds that the person can read, once it has come (GET /v1/folders/{id}/contents) */
  contents?: components['schemas']['TrashContents']
  onConfirm: () => void
  onCancel: () => void
}

/**
 * Before a folder moves into another matter, by a drag or by Move To… (folders and Trash spec 7.5, the owner's A): "Move
 * "Old Drafts" into 1003.2 – Licence Review?", with what its documents will take there. Only the documents the person can
 * read are counted, and a folder with none says nothing of them. Move, which has the focus, moves it, as it can be moved
 * back; Cancel and Esc leave it where it is.
 */
export function CrossMatter({ name, matter, contents, onConfirm, onCancel }: CrossMatterProps) {
  const documents = contents?.documents ?? 0
  return (
    <Dialog title={t('crossMatter.title', { name, matter })} isOpen onOpenChange={(open) => !open && onCancel()}>
      {(close) => (
        <>
          {documents > 0 && <p>{plural('crossMatter.line', documents, { number: matterParts(matter).number })}</p>}
          <div className={styles.actions}>
            <Button
              variant="default"
              autoFocus
              onPress={() => {
                // Answered before the dialog closes, which would answer Cancel
                onConfirm()
                close()
              }}
            >
              {t('crossMatter.confirm')}
            </Button>
            <Button onPress={close}>{t('common.cancel')}</Button>
          </div>
        </>
      )}
    </Dialog>
  )
}
