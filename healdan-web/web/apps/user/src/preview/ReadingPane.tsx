// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useEffect, useState } from 'react'
import type { components } from '@healdan/api'
import { Button, Icon, t } from '@healdan/ui'
import { ErrorPanel } from '../frame/ErrorPanel'
import { useRightRegion } from '../frame/RightRegion'
import { useDocument } from '../queries'
import { PreviewBody } from './PreviewBody'
import styles from './readingPane.module.css'

export interface ReadingPaneProps {
  /** The selected document, if one is; the pane waits for a click without one */
  docNumber?: number
  /** Its row where it was listed, shown at once while the document comes */
  row?: components['schemas']['DocumentView']
}

/**
 * How long a newly selected row stays selected before the pane asks for its preview: a row arrowed past is never read, so
 * the server records no view of it (the Audit Trail's Viewed)
 */
export const PANE_SETTLE_MS = 300

/** The value once it has stayed the same for `ms`; the first value at once */
function useSettled<T>(value: T, ms: number): T {
  const [settled, setSettled] = useState(value)
  useEffect(() => {
    const timer = setTimeout(() => setSettled(value), ms)
    return () => clearTimeout(timer)
  }, [value, ms])
  return settled
}

/**
 * The docked reading pane (spec section 6.4): Quick Look's preview in the right region, which takes the details' place
 * and previews each row you click or arrow to, once it has stayed selected for PANE_SETTLE_MS, until you close it, which
 * brings the details back.
 */
export function ReadingPane({ docNumber, row }: ReadingPaneProps) {
  const region = useRightRegion()
  const settled = useSettled(docNumber, PANE_SETTLE_MS)
  return (
    <div className={styles.pane}>
      <div className={styles.bar}>
        <h2 className={styles.title}>{docNumber === undefined ? t('preview.region') : (row?.name ?? t('preview.region'))}</h2>
        <Button variant="quiet" aria-label={t('preview.closePane')} onPress={region.close}>
          <Icon name="close" size={18} />
        </Button>
      </div>
      {docNumber === undefined ? (
        <p className={styles.hint}>{t('preview.paneHint')}</p>
      ) : settled === docNumber ? (
        <Shown docNumber={docNumber} row={row} />
      ) : (
        <p className={styles.hint}>{t('common.loading')}</p>
      )}
    </div>
  )
}

function Shown({ docNumber, row }: { docNumber: number; row?: components['schemas']['DocumentView'] }) {
  const document = useDocument(docNumber, row)
  if (document.isError) return <ErrorPanel error={document.error} onRetry={() => void document.refetch()} />
  if (!document.data) return <p className={styles.hint}>{t('common.loading')}</p>
  return <PreviewBody document={document.data} />
}
