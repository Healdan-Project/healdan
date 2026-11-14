// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { components } from '@healdan/api'
import { Button, Dialog, t } from '@healdan/ui'
import { holdSentence, holdState } from '../actions/checkout'
import { useMe } from '../queries'
import styles from './filing.module.css'

type S = components['schemas']

export interface VersionOrNewProps {
  /** The document whose row the file was dropped on */
  document: S['DocumentView']
  file: File
  /** `ifHeld` when the document is the person's own check-out from this page, whose check-in the save is */
  onVersion: (ifHeld: boolean) => void
  onNew: () => void
  onCancel: () => void
}

/**
 * What one file dropped on a document's row asks (spec 6.5): "Save as Version 4 of # 10431, or File as a New
 * Document?" (asked only when the file's extension is the document's, as the server requires). File as a New Document is the
 * default, so Enter files a new document. A document held by someone else, on a computer, or locked can't take a version from this page (the page is
 * no device, R14), so the question says who holds it and since when, and offers only the new document.
 */
export function VersionOrNew({ document, file, onVersion, onNew, onCancel }: VersionOrNewProps) {
  const me = useMe()
  const state = holdState(document.hold, me.data?.id)
  const sentence = holdSentence(state)
  const may = (state.kind === 'free' || state.kind === 'yours') && document.canEdit
  // Version 3.1 is on its way to 4, the next major number, as "Save as new version" counts
  const next = String(Number.parseInt(document.version, 10) + 1)
  return (
    <Dialog title={t('filing.versionTitle', { version: next, n: document.docNumber })} isOpen onOpenChange={(open) => !open && onCancel()}>
      {(close) => (
        <>
          <p className={styles.file}>{file.name}</p>
          {sentence && <p className={styles.hold}>{sentence}</p>}
          <div className={styles.actions}>
            <Button
              isDisabled={!may}
              onPress={() => {
                close()
                onVersion(state.kind === 'yours')
              }}
            >
              {t('filing.saveVersion', { version: next })}
            </Button>
            {/* The default (Enter): a drop that missed the row meant to miss it costs an extra document, not a version */}
            <Button
              variant="default"
              autoFocus
              onPress={() => {
                close()
                onNew()
              }}
            >
              {t('filing.fileNew')}
            </Button>
            <Button onPress={close}>{t('common.cancel')}</Button>
          </div>
        </>
      )}
    </Dialog>
  )
}
