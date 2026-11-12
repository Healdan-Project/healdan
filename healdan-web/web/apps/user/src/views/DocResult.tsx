// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useMemo } from 'react'
import { ApiError } from '@healdan/api'
import { t, Table } from '@healdan/ui'
import { SelectionActions } from '../actions/SelectionActions'
import { useOpenKey } from '../actions/useOpenKey'
import { DetailsPanel } from '../details/DetailsPanel'
import { QuickLook } from '../preview/QuickLook'
import { ReadingPane } from '../preview/ReadingPane'
import { usePreviewKey } from '../preview/usePreviewKey'
import { ErrorPanel } from '../frame/ErrorPanel'
import { RightRegionView, useRegionKind } from '../frame/RightRegion'
import { useOrganisingTable } from '../organising/useOrganisingTable'
import { useDocument, useLookup } from '../queries'
import { NotFound } from './NotFound'
import { Toolbar } from './Toolbar'
import { listColumns, narrowed, rowOf } from './listColumns'
import styles from './views.module.css'

export interface DocResultProps {
  /** What the address has after /doc/: `10388`, `10388.2`, `10388v2.1` */
  reference: string
}

/**
 * /doc/10388 and /doc/10388.2: the answer to a typed # (spec 6.3 and 10.1). The server's lookup resolves the address
 * as it does a # typed in the old UI, and the result is a list of that one document, selected, with its details open.
 * A document that isn't there and one the person can't read are the same answer, and the words say only the number.
 */
export function DocResult({ reference }: DocResultProps) {
  const lookup = useLookup(reference)
  if (lookup.isError) {
    const error = lookup.error
    // Not a doc # at all (400): the router's own not-found view
    if (error instanceof ApiError && error.status === 400) return <NotFound />
    if (error instanceof ApiError && error.status === 404) {
      return <NotFoundNumber reference={reference} />
    }
    return (
      <section className={styles.view}>
        <ErrorPanel error={error} onRetry={() => void lookup.refetch()} />
      </section>
    )
  }
  if (!lookup.data)
    return (
      <section className={styles.view}>
        <p className={styles.empty}>{t('common.loading')}</p>
      </section>
    )
  // A version is marked in Versions only when the address named one
  const named = !/^\d+$/.test(reference.trim())
  return <Found docNumber={lookup.data.docNumber} version={named ? lookup.data.version : undefined} />
}

// The number as typed, which is all the person knows; nothing the server said
function NotFoundNumber({ reference }: { reference: string }) {
  return (
    <section className={styles.view}>
      <p>{t('doc.notFound', { n: /^\d+/.exec(reference)?.[0] ?? reference })}</p>
    </section>
  )
}

function Found({ docNumber, version }: { docNumber: number; version?: string }) {
  const document = useDocument(docNumber)
  const found = useMemo(() => (document.data ? [document.data] : []), [document.data])
  const rows = useMemo(() => found.map(rowOf), [found])
  // The one row is the selection and stays it: the address is what selects
  const selection = useMemo(() => new Set(rows.map((row) => row.id)), [rows])
  const preview = usePreviewKey(() => {})
  const openDocument = useOpenKey(found, () => {})
  // Rename, Move To… and Delete, with F2 and Delete, as in every list of documents (R95)
  const organising = useOrganisingTable(found, selection)
  const columns = useRegionKind({ preview: true, details: true }) === 'preview' ? narrowed(listColumns) : listColumns
  if (document.isError) {
    // The document went away after the lookup found it: the same words as a lookup that found nothing
    if (document.error instanceof ApiError && document.error.status === 404) return <NotFoundNumber reference={String(docNumber)} />
    return (
      <section className={styles.view}>
        <ErrorPanel error={document.error} onRetry={() => void document.refetch()} />
      </section>
    )
  }
  return (
    <section className={styles.withRegion}>
      <div className={`${styles.view} ${styles.folder}`}>
        <h1 className={styles.title}>{t('doc.heading', { n: docNumber })}</h1>
        <Toolbar label={t('doc.actions')}>
          <SelectionActions document={document.data} selected={organising.selected} />
        </Toolbar>
        <Table
          aria-label={t('doc.heading', { n: docNumber })}
          columns={columns}
          rows={rows}
          selectionMode="single"
          selection={selection}
          onSelectionChange={() => {}}
          onOpen={openDocument}
          onPreview={preview}
          {...organising.tableProps}
          empty={t('common.loading')}
        />
      </div>
      <RightRegionView
        details={<DetailsPanel docNumber={docNumber} row={document.data} version={version} context="search" />}
        preview={<ReadingPane docNumber={docNumber} row={document.data} />}
      />
      <QuickLook documents={document.data ? [document.data] : []} />
    </section>
  )
}
