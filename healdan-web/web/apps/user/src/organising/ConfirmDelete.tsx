// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { components } from '@healdan/api'
import { Button, Dialog, plural, t } from '@healdan/ui'
import type { OrganisingItems } from '../actions/batch'
import { holdsLine } from './ConfirmRemove'
import styles from './organising.module.css'

export interface ConfirmDeleteProps {
  items: OrganisingItems
  /** What the one folder asked about holds that the person can read, once it has come (GET /v1/folders/{id}/contents) */
  contents?: components['schemas']['TrashContents']
  onConfirm: () => void
  onCancel: () => void
}

/**
 * "Move "Old Drafts" to the Trash?", with what the folder holds, or "Move 4 items to the Trash?" naming each, folders
 * first as the list shows them (folders and Trash spec 7.4; plan correction 14), and where they can be restored from.
 * Only what the person can read is counted, and an empty folder says nothing of it. Enter confirms (the items can be
 * restored), Esc cancels.
 */
export function ConfirmDelete({ items, contents, onConfirm, onCancel }: ConfirmDeleteProps) {
  const names = [...items.folders.map((folder) => ({ key: `f${folder.id}`, name: folder.name })), ...items.documents.map((document) => ({ key: `d${document.docNumber}`, name: document.name }))]
  const [one] = names
  const single = names.length === 1 && one !== undefined
  const holds = single && items.folders.length === 1 && contents && contents.folders + contents.documents > 0 ? holdsLine(contents) : undefined
  return (
    <Dialog title={single ? t('delete.oneTitle', { name: one.name }) : plural('delete.manyTitle', names.length)} isOpen onOpenChange={(open) => !open && onCancel()}>
      {(close) => (
        <>
          {holds && <p>{holds}</p>}
          {!single && (
            <ul className={styles.names}>
              {names.map((item) => (
                <li key={item.key}>{item.name}</li>
              ))}
            </ul>
          )}
          <p>{single ? t('delete.trashNote') : t('delete.trashNoteMany')}</p>
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
